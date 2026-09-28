package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.chain.ChainErrors;
import com.qhx.back.chain.ChainTxState;
import com.qhx.back.chain.ParamsDigest;
import com.qhx.back.chain.StageConfirmedListener;
import com.qhx.back.chain.StageProbe;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.chain.TxOutcome;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.context.AddressContext;
import com.qhx.back.context.UserContext;
import com.qhx.back.enums.UserRole;
import com.qhx.back.exception.AuthException;
import com.qhx.back.exception.ChainTxException;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.mapper.TraceBatchMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.vo.ChainTxVerifyVO;
import com.qhx.back.service.ChainTxService;
import com.qhx.back.util.UserAddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class ChainTxServiceImpl implements ChainTxService
{
    static final int MAX_TRACE_NUMBER_LENGTH = 128;
    // PENDING 超过这个时间仍未推进到 SUBMITTED，说明进程在发请求之前中断了
    static final long PENDING_STALE_MS = 60_000;
    private static final int MAX_REASON_LENGTH = 500;

    @Autowired
    private ChainTxMapper chainTxMapper;
    @Autowired
    private TraceBatchMapper batchMapper;
    @Value("${contract.address}")
    private String v2Address;
    @Value("${contract.v3.address:0x0}")
    private String v3Address;
    @Autowired
    private WeBaseClient weBaseClient;
    // 阶段交易确认后的链下处理（刷新读模型、绑定文件）；没有实现时为空
    @Autowired(required = false)
    private List<StageConfirmedListener> confirmedListeners;

    @Override
    public ChainTx submit(String funcName, List<Object> params)
    {
        return doSubmit("V2", funcName, params, null, null, null);
    }

    @Override
    public ChainTx submitToContract(String version, String funcName, List<Object> params)
    {
        return doSubmit(version, funcName, params, null, null, null);
    }

    @Override
    public ChainTx submitStage(TraceStage stage, List<Object> params)
    {
        return submitStage(stage, params, null);
    }

    @Override
    public ChainTx submitStage(TraceStage stage, List<Object> params, Runnable guard)
    {
        Object first = params.isEmpty() ? null : params.get(0);
        String traceNumber = first == null ? "" : String.valueOf(first);
        if (traceNumber.length() > MAX_TRACE_NUMBER_LENGTH) {
            throw new IllegalArgumentException("溯源号长度不能超过 " + MAX_TRACE_NUMBER_LENGTH);
        }
        return doSubmit(versionFor(traceNumber), stage.writeFunction(), params, stage, traceNumber, guard);
    }

    private String versionFor(String traceNumber)
    {
        TraceBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>()
                .eq(TraceBatch::getTraceNumber, traceNumber));
        if (batch == null) throw new IllegalStateException("阶段交易没有批次合约绑定：" + traceNumber);
        String version = batch.getContractVersion();
        if (version == null) version = "V2";
        String configured = "V3".equals(version) ? v3Address : v2Address;
        if (!"V2".equals(version) && !"V3".equals(version)) throw new IllegalStateException("未知批次合约版本：" + version);
        if (batch.getContractAddress() == null || !batch.getContractAddress().equalsIgnoreCase(configured)) {
            throw new IllegalStateException("批次绑定的合约地址与当前配置不一致，拒绝发送交易");
        }
        return version;
    }

    private ChainTx doSubmit(String version, String funcName, List<Object> params, TraceStage stage, String traceNumber, Runnable guard)
    {
        String signer = AddressContext.getAddress();
        if (!UserAddressUtil.isLegalAddress(signer)) {
            throw new IllegalStateException("当前会话没有绑定合法的链上地址，拒绝发送交易");
        }
        String digest = ParamsDigest.of(params);
        String bizKey = stage == null ? version + ":" + funcName + ":" + digest.substring(0, 16) : "trace:" + traceNumber + ":" + stage.name();

        Date now = new Date();
        ChainTx tx = new ChainTx();
        tx.setBizKey(bizKey);
        // 只有阶段交易占用业务键：合约保证每阶段只写一次，未决时再发一笔没有意义且会让查证变得含糊
        tx.setInflightKey(stage == null ? null : bizKey);
        tx.setTraceNumber(stage == null ? null : traceNumber);
        tx.setStage(stage == null ? null : stage.code());
        tx.setFuncName(funcName);
        tx.setParamsDigest(digest);
        tx.setSigner(signer);
        tx.setState(ChainTxState.PENDING.name());
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        try {
            // 1. 先落库提交意图，再发请求
            chainTxMapper.insert(tx);
        } catch (DuplicateKeyException e) {
            ChainTx existing = findInflight(bizKey);
            String which = existing == null ? "" : " #" + existing.getId() + "（" + existing.getState() + "）";
            throw new ChainTxException(409, "该阶段已有未确认的交易" + which
                    + "，请先调用查证接口 POST /chain-tx/{id}/verify 确认结果，查证前不能重复提交", existing);
        }

        // 1.5 业务键已占住：此时再做一次业务校验，与「变更交接对象」之间不会出现两边都通过的窗口
        if (guard != null) {
            try {
                guard.run();
            } catch (RuntimeException e) {
                finish(tx.getId(), ChainTxState.FAILED, StrUtil.maxLength("发送前校验未通过，交易没有发出：" + e.getMessage(), MAX_REASON_LENGTH), "NOT_SENT");
                throw e;
            }
        }

        // 2. 发请求前推进到 SUBMITTED：此后进程无论在哪一步中断，这条记录都表示「可能已发出」
        chainTxMapper.update(null, new LambdaUpdateWrapper<ChainTx>()
                .set(ChainTx::getState, ChainTxState.SUBMITTED.name())
                .set(ChainTx::getUpdatedAt, new Date())
                .eq(ChainTx::getId, tx.getId()));

        TxOutcome outcome;
        try {
            outcome = weBaseClient.sendTransaction(version, funcName, params);
        } catch (RuntimeException e) {
            log.error("交易 #{} 发送时出现异常，按结果未知处理", tx.getId(), e);
            outcome = TxOutcome.unknown(null, null, "发送过程中出现异常：" + e);
        }

        // 3. 按结果更新
        ChainTx saved = applyOutcome(tx.getId(), outcome, null);
        if (outcome.getKind() == TxOutcome.Kind.CONFIRMED) {
            notifyConfirmed(saved);
            return saved;
        }
        ChainErrors.Mapped mapped = ChainErrors.of(outcome);
        throw new ChainTxException(mapped.status, mapped.message + "（交易记录 #" + tx.getId() + "）", saved);
    }

    @Override
    public ChainTx get(Long id)
    {
        ChainTx tx = chainTxMapper.selectById(id);
        if (tx == null) {
            throw new ChainTxException(404, "交易记录不存在", null);
        }
        UserAccount user = UserContext.getUser();
        boolean admin = user != null && UserRole.ADMIN.name().equals(user.getRole());
        if (!admin && (user == null || !tx.getSigner().equalsIgnoreCase(user.getChainAddress()))) {
            throw new AuthException(403, "只能查看或查证自己签名的交易");
        }
        return tx;
    }

    @Override
    public ChainTxVerifyVO verify(Long id)
    {
        ChainTx tx = get(id);
        ChainTxState state = ChainTxState.valueOf(tx.getState());
        if (state == ChainTxState.CONFIRMED || state == ChainTxState.FAILED) {
            return new ChainTxVerifyVO("ALREADY_FINAL", "记录已是终态，无需查证", tx);
        }
        if (state == ChainTxState.PENDING) {
            if (System.currentTimeMillis() - tx.getCreatedAt().getTime() < PENDING_STALE_MS) {
                throw new ChainTxException(409, "交易正在发送，请稍后再查证", tx);
            }
            // PENDING 只会在「写入意图之后、推进到 SUBMITTED 之前」进程中断时残留，此时一定还没有发请求
            ChainTx done = finish(id, ChainTxState.FAILED, "进程在发出请求前中断，交易没有发出", "NOT_SENT");
            return new ChainTxVerifyVO("NOT_SENT", "交易没有发出，可以重新提交", done);
        }

        // SUBMITTED / UNKNOWN：有哈希先查回执
        if (tx.getTxHash() != null) {
            TxOutcome receipt = weBaseClient.queryReceipt(tx.getTxHash());
            if (receipt.getKind() == TxOutcome.Kind.CONFIRMED) {
                ChainTx done = applyOutcome(id, receipt, "RECEIPT_CONFIRMED");
                notifyConfirmed(done);
                return new ChainTxVerifyVO("RECEIPT_CONFIRMED", "按交易哈希查到成功回执", done);
            }
            if (receipt.getKind() == TxOutcome.Kind.REVERTED) {
                return new ChainTxVerifyVO("RECEIPT_FAILED", ChainErrors.of(receipt).message,
                        applyOutcome(id, receipt, "RECEIPT_FAILED"));
            }
        }

        TraceStage stage = TraceStage.ofCode(tx.getStage());
        if (stage == null) {
            ChainTx reloaded = markVerify(id, "INCONCLUSIVE");
            return new ChainTxVerifyVO("INCONCLUSIVE",
                    "拿不到回执，且不是溯源阶段交易，无法靠读链判定，请稍后再查证或人工核对", reloaded);
        }

        StageProbe.Result probe = new StageProbe(weBaseClient, versionFor(tx.getTraceNumber()))
                .probe(stage, tx.getTraceNumber(), tx.getSigner(), tx.getParamsDigest());
        switch (probe.conclusion) {
            case WRITTEN_BY_SIGNER: {
                // 每个阶段只写一次：同一业务键已有另一条带成功回执的记录时，写入的只能是那一笔
                ChainTx other = chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                                .eq(ChainTx::getBizKey, tx.getBizKey())
                                .eq(ChainTx::getState, ChainTxState.CONFIRMED.name())
                                .ne(ChainTx::getId, id))
                        .stream().findFirst().orElse(null);
                if (other != null) {
                    String note = "该阶段已由交易记录 #" + other.getId() + " 写入（每个阶段只能写一次），本次交易未生效";
                    return new ChainTxVerifyVO("CONFLICT", note, finish(id, ChainTxState.FAILED, note, "CONFLICT"));
                }
                ChainTx done = finish(id, ChainTxState.CONFIRMED, null, "STATE_CONFIRMED");
                notifyConfirmed(done);
                return new ChainTxVerifyVO("STATE_CONFIRMED", probe.note + "；没有拿到回执，交易哈希与块高未知", done);
            }
            case CONFLICT:
                return new ChainTxVerifyVO("CONFLICT", probe.note,
                        finish(id, ChainTxState.FAILED, probe.note, "CONFLICT"));
            case INCONCLUSIVE:
                return new ChainTxVerifyVO("INCONCLUSIVE", probe.note,
                        markVerify(id, "INCONCLUSIVE"));
            case NOT_WRITTEN:
            default: {
                // 仍是 UNKNOWN（原交易之后是否上链未知），但释放业务键，允许用户显式重新提交；
                // 原交易若之后才上链，重新提交的那笔会被合约以「已写入」拒绝，不会重复写入
                chainTxMapper.update(null, new LambdaUpdateWrapper<ChainTx>()
                        .set(ChainTx::getState, ChainTxState.UNKNOWN.name())
                        .set(ChainTx::getInflightKey, null)
                        .set(ChainTx::getVerifyResult, "NOT_WRITTEN")
                        .set(ChainTx::getUpdatedAt, new Date())
                        .eq(ChainTx::getId, id));
                return new ChainTxVerifyVO("NOT_WRITTEN", probe.note + "；可以重新提交。原交易若之后才上链，"
                        + "重新提交会被合约拒绝而不会重复写入，届时可再次查证本记录", chainTxMapper.selectById(id));
            }
        }
    }

    /** 只对状态确为 CONFIRMED 的阶段交易回调；回调失败只记日志 */
    private void notifyConfirmed(ChainTx tx)
    {
        if (confirmedListeners == null || tx == null || tx.getStage() == null
                || !ChainTxState.CONFIRMED.name().equals(tx.getState())) {
            return;
        }
        for (StageConfirmedListener listener : confirmedListeners) {
            try {
                listener.onStageConfirmed(tx);
            } catch (RuntimeException e) {
                log.error("交易 #{} 已确认，但链下后续处理 {} 失败（可由管理员重建读模型补齐）", tx.getId(),
                        listener.getClass().getSimpleName(), e);
            }
        }
    }

    private ChainTx applyOutcome(Long id, TxOutcome outcome, String verifyResult)
    {
        LambdaUpdateWrapper<ChainTx> update = new LambdaUpdateWrapper<ChainTx>()
                .set(ChainTx::getUpdatedAt, new Date())
                .eq(ChainTx::getId, id);
        if (outcome.getTxHash() != null) {
            update.set(ChainTx::getTxHash, outcome.getTxHash());
        }
        if (outcome.getBlockNumber() != null) {
            update.set(ChainTx::getBlockNumber, outcome.getBlockNumber());
        }
        if (outcome.getReceiptStatus() != null) {
            update.set(ChainTx::getReceiptStatus, outcome.getReceiptStatus());
        }
        if (verifyResult != null) {
            update.set(ChainTx::getVerifyResult, verifyResult);
        }
        switch (outcome.getKind()) {
            case CONFIRMED:
                update.set(ChainTx::getState, ChainTxState.CONFIRMED.name())
                        .set(ChainTx::getErrorReason, null)
                        .set(ChainTx::getInflightKey, null);
                break;
            case UNKNOWN:
                update.set(ChainTx::getState, ChainTxState.UNKNOWN.name())
                        .set(ChainTx::getErrorReason, reason(outcome));
                break;
            default:
                update.set(ChainTx::getState, ChainTxState.FAILED.name())
                        .set(ChainTx::getErrorReason, reason(outcome))
                        .set(ChainTx::getInflightKey, null);
        }
        chainTxMapper.update(null, update);
        return chainTxMapper.selectById(id);
    }

    private ChainTx finish(Long id, ChainTxState state, String reason, String verifyResult)
    {
        chainTxMapper.update(null, new LambdaUpdateWrapper<ChainTx>()
                .set(ChainTx::getState, state.name())
                .set(ChainTx::getErrorReason, reason)
                .set(ChainTx::getInflightKey, null)
                .set(ChainTx::getVerifyResult, verifyResult)
                .set(ChainTx::getUpdatedAt, new Date())
                .eq(ChainTx::getId, id));
        return chainTxMapper.selectById(id);
    }

    private ChainTx markVerify(Long id, String verifyResult)
    {
        chainTxMapper.update(null, new LambdaUpdateWrapper<ChainTx>()
                .set(ChainTx::getVerifyResult, verifyResult)
                .set(ChainTx::getUpdatedAt, new Date())
                .eq(ChainTx::getId, id));
        return chainTxMapper.selectById(id);
    }

    private ChainTx findInflight(String bizKey)
    {
        return chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>().eq(ChainTx::getInflightKey, bizKey))
                .stream().findFirst().orElse(null);
    }

    private static String reason(TxOutcome outcome)
    {
        String code = outcome.getFrontCode() == null ? "" : " code=" + outcome.getFrontCode();
        return StrUtil.maxLength(outcome.getKind() + code + ": " + outcome.getReason(), MAX_REASON_LENGTH);
    }
}
