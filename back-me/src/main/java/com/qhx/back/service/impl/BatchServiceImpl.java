package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.chain.ChainTxState;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.context.UserContext;
import com.qhx.back.enums.UserRole;
import com.qhx.back.exception.AuthException;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.TraceAssignmentLogMapper;
import com.qhx.back.mapper.TraceBatchMapper;
import com.qhx.back.mapper.TraceCorrectionMapper;
import com.qhx.back.mapper.TraceReadModelMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.TraceAssignmentLog;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.model.TraceCorrection;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.to.AssignTo;
import com.qhx.back.model.to.CorrectionTo;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.model.vo.BatchListRow;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.model.vo.PageResult;
import com.qhx.back.service.BatchService;
import com.qhx.back.service.ChainTxService;
import com.qhx.back.service.FileService;
import com.qhx.back.service.ReadModelService;
import com.qhx.back.trace.ChainTraceReader;
import com.qhx.back.trace.TraceFields;
import com.qhx.back.trace.TraceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BatchServiceImpl implements BatchService
{
    /** 阶段展示状态 */
    static final String NOT_STARTED = "NOT_STARTED";
    static final String PENDING = "PENDING";
    static final String CONFIRMED = "CONFIRMED";
    static final String FAILED = "FAILED";
    /** UNKNOWN 记录经查证确认当时未写入、已释放业务键：可以重新提交 */
    static final String RELEASED = "RELEASED";

    private static final Map<TraceStage, String> STAGE_LABELS = new EnumMap<>(TraceStage.class);

    static {
        STAGE_LABELS.put(TraceStage.PRODUCTION, "生产");
        STAGE_LABELS.put(TraceStage.DISTRIBUTION, "分销");
        STAGE_LABELS.put(TraceStage.RETAIL, "零售");
    }

    @Autowired
    private TraceBatchMapper batchMapper;
    @Autowired
    private TraceAssignmentLogMapper assignmentLogMapper;
    @Autowired
    private TraceCorrectionMapper correctionMapper;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private ChainTxMapper chainTxMapper;
    @Autowired
    private ChainTxService chainTxService;
    @Autowired
    private WeBaseClient weBaseClient;
    @Autowired
    private FileService fileService;
    @Autowired
    private ReadModelService readModelService;
    @Autowired
    private TraceReadModelMapper readModelMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Value("${contract.v3.address:0x0}")
    private String v3Address;
    @Value("${contract.address:0x0}")
    private String v2Address;

    // ---------------------------------------------------------------- 三阶段写入

    @Override
    public ChainTx submitProduction(ProducerTo to)
    {
        UserAccount me = currentUser();
        String tn = StrUtil.trim(to.getTraceNumber());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("companyName", StrUtil.trim(to.getCompanyName()));
        values.put("productName", StrUtil.trim(to.getProductName()));
        values.put("productionLocation", StrUtil.trim(to.getProductionLocation()));
        values.put("variety", StrUtil.trim(to.getVariety()));
        values.put("productionBatch", StrUtil.trim(to.getProductionBatch()));
        values.put("productionCert", StrUtil.trim(to.getProductionCert()));
        values.put("productTime", StrUtil.trim(to.getProductTime()));
        TraceValidator v = TraceValidator.create()
                .traceNumber("traceNumber", tn)
                .stageFields(TraceStage.PRODUCTION, values)
                .required("distributorUsername", to.getDistributorUsername());
        UserAccount distributor = resolvePartner(v, "distributorUsername", to.getDistributorUsername(), UserRole.DISTRIBUTOR);
        // 生产认证必须是本账号经 /upload 上传、尚未被使用的文件
        fileService.checkUsable(v, "productionCert", (String) values.get("productionCert"), me, tn, TraceStage.PRODUCTION);
        v.throwIfInvalid();
        TraceBatch batch = findBatch(tn);
        if ((batch == null || "V3".equals(version(batch))) && (v3Address == null || "0x0".equals(v3Address))) {
            throw new BusinessException(503, "未配置 v3 合约地址，不能创建新批次");
        }
        ChainTraceReader chain = batch == null ? new ChainTraceReader(weBaseClient, "V3") : reader(batch);
        List<String> actors = chain.actors(tn);
        if (actors == null && batch == null && new ChainTraceReader(weBaseClient).actors(tn) != null) {
            throw new BusinessException(409, "该溯源号已存在于 v2 合约，请换一个溯源号");
        }
        if (actors != null) {
            String producer = chain.actor(actors, TraceStage.PRODUCTION);
            throw new BusinessException(409, me.getChainAddress().equalsIgnoreCase(producer)
                    ? "该溯源号的生产信息已上链，不能重复录入"
                    : "该溯源号已在链上由其他账户登记，请换一个溯源号");
        }

        // 占用溯源号：唯一索引兜底并发；已存在且属于本人时视为重新提交（上次失败或查证后未写入）
        if (batch == null) {
            TraceBatch created = new TraceBatch();
            created.setTraceNumber(tn);
            created.setProductName((String) values.get("productName"));
            created.setProducerId(me.getId());
            created.setDistributorId(distributor.getId());
            created.setContractVersion("V3");
            created.setContractAddress(v3Address);
            created.setCreatedAt(new Date());
            created.setUpdatedAt(new Date());
            try {
                batchMapper.insert(created);
                logAssignment(tn, TraceStage.DISTRIBUTION, null, distributor.getId(), me.getId(), "建档时指定");
            } catch (DuplicateKeyException e) {
                batch = findBatch(tn);
            }
        }
        if (batch != null) {
            if (!me.getId().equals(batch.getProducerId())) {
                throw new AuthException(403, "该溯源号已由其他生产商建档");
            }
            if (!distributor.getId().equals(batch.getDistributorId())) {
                doReassign(batch, TraceStage.DISTRIBUTION, distributor, me, "重新提交生产信息时变更");
            }
        }

        // 占用文件：交易确认后据此绑定；未决期间不会被当成孤儿清理
        fileService.claim((String) values.get("productionCert"), me, tn, TraceStage.PRODUCTION);
        List<Object> params = new ArrayList<>();
        params.add(tn);
        params.addAll(values.values());
        if ("V3".equals(batch == null ? "V3" : version(batch))) params.add(distributor.getChainAddress());
        return chainTxService.submitStage(TraceStage.PRODUCTION, params);
    }

    @Override
    public ChainTx submitDistribution(DistributorTo to)
    {
        UserAccount me = currentUser();
        String tn = StrUtil.trim(to.getTraceNumber());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("companyName", StrUtil.trim(to.getCompanyName()));
        values.put("storageCondition", StrUtil.trim(to.getStorageCondition()));
        values.put("transportMethod", StrUtil.trim(to.getTransportMethod()));
        values.put("distributeBatch", StrUtil.trim(to.getDistributeBatch()));
        values.put("storageLocation", StrUtil.trim(to.getStorageLocation()));
        values.put("distributePrice", to.getDistributePrice());
        values.put("distributeQuantity", to.getDistributeQuantity());
        values.put("inspectionReport", StrUtil.trim(to.getInspectionReport()));
        TraceValidator v = TraceValidator.create()
                .traceNumber("traceNumber", tn)
                .stageFields(TraceStage.DISTRIBUTION, values)
                .required("retailerUsername", to.getRetailerUsername());
        UserAccount retailer = resolvePartner(v, "retailerUsername", to.getRetailerUsername(), UserRole.RETAILER);
        fileService.checkUsable(v, "inspectionReport", (String) values.get("inspectionReport"), me, tn, TraceStage.DISTRIBUTION);
        v.throwIfInvalid();

        TraceBatch batch = requireBatch(tn);
        if ("V3".equals(version(batch))) {
            readModelService.refresh(tn);
            batch = requireBatch(tn);
        }
        if (!me.getId().equals(batch.getDistributorId())) {
            throw new AuthException(403, "只有该批次指定的分销商可以录入分销信息");
        }
        ChainTraceReader chain = reader(batch);
        List<String> actors = chain.actors(tn);
        if (actors == null) {
            throw new BusinessException(409, "该批次的生产信息尚未上链，不能录入分销信息");
        }
        UserAccount producer = userAccountMapper.selectById(batch.getProducerId());
        if (producer == null || !producer.getChainAddress().equalsIgnoreCase(chain.actor(actors, TraceStage.PRODUCTION))) {
            throw new BusinessException(409, "链上生产记录的写入者不是本批次的生产商，拒绝交接");
        }
        if ("V3".equals(version(batch))) {
            List<String> designated = chain.designations(tn);
            if (designated == null || !me.getChainAddress().equalsIgnoreCase(designated.get(0))) {
                log.warn("v3 批次 {} 分销台账与链上指定不一致：台账账号 {}，链上地址 {}",
                        tn, me.getId(), designated == null ? null : designated.get(0));
                throw new AuthException(403, "链上指定分销商已变更，请核对批次详情并联系管理员同步账号");
            }
        }

        if (!"V3".equals(version(batch))) {
            if (batch.getRetailerId() == null) {
                assignFirst(batch, TraceStage.RETAIL, retailer, me);
            } else if (!retailer.getId().equals(batch.getRetailerId())) {
                doReassign(batch, TraceStage.RETAIL, retailer, me, "录入分销信息时变更");
            }
        }

        values.put("distributePrice", TraceValidator.toLong(to.getDistributePrice()));
        values.put("distributeQuantity", TraceValidator.toLong(to.getDistributeQuantity()));
        fileService.claim((String) values.get("inspectionReport"), me, tn, TraceStage.DISTRIBUTION);
        List<Object> params = new ArrayList<>();
        params.add(tn);
        params.addAll(values.values());
        if ("V3".equals(version(batch))) params.add(retailer.getChainAddress());
        Long myId = me.getId();
        return chainTxService.submitStage(TraceStage.DISTRIBUTION, params,
                () -> requireStillAssigned(tn, TraceStage.DISTRIBUTION, myId));
    }

    @Override
    public ChainTx submitRetail(RetailerTo to)
    {
        UserAccount me = currentUser();
        String tn = StrUtil.trim(to.getTraceNumber());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("companyName", StrUtil.trim(to.getCompanyName()));
        values.put("salePrice", to.getSalePrice());
        values.put("saleQuantity", to.getSaleQuantity());
        values.put("shelfLife", to.getShelfLife());
        values.put("invoiceNo", StrUtil.trim(to.getInvoiceNo()));
        values.put("saleTime", StrUtil.trim(to.getSaleTime()));
        TraceValidator.create()
                .traceNumber("traceNumber", tn)
                .stageFields(TraceStage.RETAIL, values)
                .throwIfInvalid();

        TraceBatch batch = requireBatch(tn);
        if ("V3".equals(version(batch))) {
            readModelService.refresh(tn);
            batch = requireBatch(tn);
        }
        if (!me.getId().equals(batch.getRetailerId())) {
            throw new AuthException(403, "只有该批次指定的零售商可以录入零售信息");
        }
        // 依赖上一阶段的规则以链上数据为准
        ChainTraceReader chain = reader(batch);
        if ("V3".equals(version(batch))) {
            List<String> designated = chain.designations(tn);
            if (designated == null || !me.getChainAddress().equalsIgnoreCase(designated.get(1))) {
                log.warn("v3 批次 {} 零售台账与链上指定不一致：台账账号 {}，链上地址 {}",
                        tn, me.getId(), designated == null ? null : designated.get(1));
                throw new AuthException(403, "链上指定零售商已变更，请核对批次详情并联系管理员同步账号");
            }
        }
        Map<String, Object> distribution = chain.stage(tn, TraceStage.DISTRIBUTION);
        if (distribution == null) {
            throw new BusinessException(409, "该批次的分销信息尚未上链，不能录入零售信息");
        }
        Map<String, Object> production = chain.stage(tn, TraceStage.PRODUCTION);
        TraceValidator.create()
                .notMoreThan("saleQuantity", to.getSaleQuantity(), "分销数量", (Long) distribution.get("distributeQuantity"))
                .notBefore("saleTime", (String) values.get("saleTime"), "生产日期", production == null ? null : (String) production.get("productTime"))
                .throwIfInvalid();

        values.put("salePrice", TraceValidator.toLong(to.getSalePrice()));
        values.put("saleQuantity", TraceValidator.toLong(to.getSaleQuantity()));
        values.put("shelfLife", TraceValidator.toLong(to.getShelfLife()));
        List<Object> params = new ArrayList<>();
        params.add(tn);
        params.addAll(values.values());
        Long myId = me.getId();
        return chainTxService.submitStage(TraceStage.RETAIL, params,
                () -> requireStillAssigned(tn, TraceStage.RETAIL, myId));
    }

    // ---------------------------------------------------------------- 交接对象

    @Override
    public Map<String, Object> reassign(String traceNumber, TraceStage stage, AssignTo to)
    {
        UserAccount me = currentUser();
        if (stage != TraceStage.DISTRIBUTION && stage != TraceStage.RETAIL) {
            throw new BusinessException(400, "只能指定分销商或零售商");
        }
        TraceBatch batch = requireBatch(traceNumber);
        if ("V3".equals(version(batch))) {
            readModelService.refresh(traceNumber);
            batch = requireBatch(traceNumber);
        }
        Long owner = stage == TraceStage.DISTRIBUTION ? batch.getProducerId() : batch.getDistributorId();
        if (!me.getId().equals(owner)) {
            throw new AuthException(403, stage == TraceStage.DISTRIBUTION
                    ? "只有本批次的生产商可以指定分销商" : "只有本批次指定的分销商可以指定零售商");
        }
        UserRole role = stage == TraceStage.DISTRIBUTION ? UserRole.DISTRIBUTOR : UserRole.RETAILER;
        TraceValidator v = TraceValidator.create().required("username", to.getUsername());
        UserAccount target = resolvePartner(v, "username", to.getUsername(), role);
        if (to.getReason() != null && to.getReason().length() > 200) {
            v.add("reason", "长度不能超过 200");
        }
        v.throwIfInvalid();
        if ("V3".equals(version(batch)) && stage == TraceStage.RETAIL) {
            throw new BusinessException(409, "v3 零售商在分销写入时上链指定，合约未提供零售改派接口");
        }
        Long current = stage == TraceStage.DISTRIBUTION ? batch.getDistributorId() : batch.getRetailerId();
        if ("V3".equals(version(batch)) && stage == TraceStage.DISTRIBUTION && !target.getId().equals(current)) {
            ChainTraceReader chain = reader(batch);
            List<String> actors = chain.actors(traceNumber);
            if (actors != null) {
                List<String> designated = chain.designations(traceNumber);
                if (designated == null || !target.getChainAddress().equalsIgnoreCase(designated.get(0))) {
                    chainTxService.submitToContract("V3", "redesignateDistributor",
                            Arrays.asList(traceNumber, target.getChainAddress()));
                }
                // 只有确认上链或读链确认目标已生效后才更改 MySQL 台账。
            }
        }
        if (current == null) {
            assignFirst(batch, stage, target, me);
        } else {
            doReassign(batch, stage, target, me, StrUtil.trim(to.getReason()));
        }
        return detail(traceNumber);
    }

    /** 首次指定（原来为空）：条件更新防并发 */
    private void assignFirst(TraceBatch batch, TraceStage stage, UserAccount target, UserAccount operator)
    {
        transactionTemplate.executeWithoutResult(status -> {
            LambdaUpdateWrapper<TraceBatch> update = new LambdaUpdateWrapper<TraceBatch>()
                    .set(TraceBatch::getUpdatedAt, new Date())
                    .eq(TraceBatch::getId, batch.getId());
            if (stage == TraceStage.DISTRIBUTION) {
                update.set(TraceBatch::getDistributorId, target.getId()).isNull(TraceBatch::getDistributorId);
            } else {
                update.set(TraceBatch::getRetailerId, target.getId()).isNull(TraceBatch::getRetailerId);
            }
            if (batchMapper.update(null, update) == 0) {
                throw new BusinessException(409, "交接对象已被其他请求修改，请刷新后重试");
            }
            logAssignment(batch.getTraceNumber(), stage, null, target.getId(), operator.getId(), "首次指定");
        });
        if (stage == TraceStage.DISTRIBUTION) {
            batch.setDistributorId(target.getId());
        } else {
            batch.setRetailerId(target.getId());
        }
    }

    /**
     * 变更交接对象。只能在该阶段写入之前：链上已写入 → 409；有未决或已确认的交易 → 409。
     * 先条件更新、再检查未决交易，同一事务内检查失败即回滚；配合写入侧的 guard（加锁重读指定关系），两边不会同时通过。
     */
    private void doReassign(TraceBatch batch, TraceStage stage, UserAccount target, UserAccount operator, String reason)
    {
        Long current = stage == TraceStage.DISTRIBUTION ? batch.getDistributorId() : batch.getRetailerId();
        if (target.getId().equals(current)) {
            return;
        }
        String label = STAGE_LABELS.get(stage);
        ChainTraceReader chain = reader(batch);
        if (chain.actor(chain.actors(batch.getTraceNumber()), stage) != null) {
            throw new BusinessException(409, label + "信息已上链，不能再变更" + label + "对象");
        }
        transactionTemplate.executeWithoutResult(status -> {
            LambdaUpdateWrapper<TraceBatch> update = new LambdaUpdateWrapper<TraceBatch>()
                    .set(TraceBatch::getUpdatedAt, new Date())
                    .eq(TraceBatch::getId, batch.getId());
            if (stage == TraceStage.DISTRIBUTION) {
                update.set(TraceBatch::getDistributorId, target.getId()).eq(TraceBatch::getDistributorId, current);
            } else {
                update.set(TraceBatch::getRetailerId, target.getId()).eq(TraceBatch::getRetailerId, current);
            }
            if (batchMapper.update(null, update) == 0) {
                throw new BusinessException(409, "交接对象已被其他请求修改，请刷新后重试");
            }
            Long busy = chainTxMapper.selectCount(new LambdaQueryWrapper<ChainTx>()
                    .eq(ChainTx::getTraceNumber, batch.getTraceNumber())
                    .eq(ChainTx::getStage, stage.code())
                    .and(w -> w.isNotNull(ChainTx::getInflightKey).or().eq(ChainTx::getState, ChainTxState.CONFIRMED.name())));
            if (busy != null && busy > 0) {
                throw new BusinessException(409, label + "阶段已有待确认或已上链的交易，不能变更" + label + "对象；待确认的请先查证");
            }
            logAssignment(batch.getTraceNumber(), stage, current, target.getId(), operator.getId(), reason);
        });
        if (stage == TraceStage.DISTRIBUTION) {
            batch.setDistributorId(target.getId());
        } else {
            batch.setRetailerId(target.getId());
        }
    }

    /** 写入侧 guard：业务键已占住后，加锁重读指定关系，确认当前用户仍是该阶段的交接对象 */
    private void requireStillAssigned(String traceNumber, TraceStage stage, Long userId)
    {
        TraceBatch locked = batchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>()
                .eq(TraceBatch::getTraceNumber, traceNumber).last("FOR UPDATE"));
        Long assigned = locked == null ? null : stage == TraceStage.DISTRIBUTION ? locked.getDistributorId() : locked.getRetailerId();
        if (!userId.equals(assigned)) {
            throw new AuthException(403, "交接对象已变更，你不再是该批次指定的" + (stage == TraceStage.DISTRIBUTION ? "分销商" : "零售商"));
        }
    }

    private void logAssignment(String traceNumber, TraceStage stage, Long from, Long to, Long operator, String reason)
    {
        TraceAssignmentLog log = new TraceAssignmentLog();
        log.setTraceNumber(traceNumber);
        log.setStage(stage.code());
        log.setFromUserId(from);
        log.setToUserId(to);
        log.setOperatorId(operator);
        log.setReason(StrUtil.maxLength(reason, 190));
        log.setCreatedAt(new Date());
        assignmentLogMapper.insert(log);
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public PageResult<Map<String, Object>> list(PageQuery page, boolean todo, String keyword)
    {
        UserAccount me = currentUser();
        Long producerId = null;
        Long distributorId = null;
        Long retailerId = null;
        Integer todoStage = null;
        switch (UserRole.parse(me.getRole())) {
            case PRODUCER:
                producerId = me.getId();
                // 轮到生产商：链上还没有生产记录（含待确认、失败）
                todoStage = todo ? 0 : null;
                break;
            case DISTRIBUTOR:
                distributorId = me.getId();
                todoStage = todo ? 1 : null;
                break;
            case RETAILER:
                retailerId = me.getId();
                todoStage = todo ? 2 : null;
                break;
            default:
                // 管理员看全部，没有「待我处理」
        }
        String like = likePattern(keyword);
        long total = readModelMapper.countBatches(producerId, distributorId, retailerId, todoStage, like);
        List<BatchListRow> batches = total <= page.offset() ? Collections.emptyList()
                : readModelMapper.pageBatches(producerId, distributorId, retailerId, todoStage, like, page.offset(), page.size);
        if (batches.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), total, page.page, page.size);
        }
        Map<String, List<ChainTx>> txByTrace = chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                        .in(ChainTx::getTraceNumber, batches.stream().map(BatchListRow::getTraceNumber).collect(Collectors.toList()))
                        .isNotNull(ChainTx::getStage)
                        .orderByAsc(ChainTx::getId))
                .stream().collect(Collectors.groupingBy(ChainTx::getTraceNumber));
        Map<Long, UserAccount> users = users(batches.stream()
                .flatMap(b -> Arrays.asList(b.getProducerId(), b.getDistributorId(), b.getRetailerId()).stream())
                .collect(Collectors.toSet()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (BatchListRow b : batches) {
            List<ChainTx> txs = txByTrace.getOrDefault(b.getTraceNumber(), Collections.emptyList());
            int reached = b.getStageReached() == null ? 0 : b.getStageReached();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceNumber", b.getTraceNumber());
            row.put("productName", b.getProductName());
            row.put("createdAt", b.getCreatedAt());
            row.put("producer", party(users.get(b.getProducerId())));
            row.put("distributor", party(users.get(b.getDistributorId())));
            row.put("retailer", party(users.get(b.getRetailerId())));
            row.put("stageReached", reached);
            Map<String, Object> stages = new LinkedHashMap<>();
            for (TraceStage stage : TraceStage.values()) {
                Map<String, Object> status = txStatus(pick(txs, stage));
                boolean onChain = reached >= stage.code();
                // 读模型显示链上已写入、但本系统没有交易记录（重建时回填的旧批次）：按链上事实显示已上链
                if (onChain && NOT_STARTED.equals(status.get("status"))) {
                    status.put("status", CONFIRMED);
                }
                status.put("onChain", onChain);
                stages.put(stage.name(), status);
            }
            row.put("stages", stages);
            result.add(row);
        }
        return new PageResult<>(result, total, page.page, page.size);
    }

    /** 关键字转 LIKE 模式：转义反斜杠、百分号、下划线；空白不过滤 */
    static String likePattern(String keyword)
    {
        if (StrUtil.isBlank(keyword)) {
            return null;
        }
        String k = keyword.trim();
        if (k.length() > 64) {
            k = k.substring(0, 64);
        }
        return "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    public Map<String, Object> detail(String traceNumber)
    {
        UserAccount me = currentUser();
        TraceBatch batch = requireBatch(traceNumber);
        boolean admin = UserRole.ADMIN.name().equals(me.getRole());
        if (!admin && !Arrays.asList(batch.getProducerId(), batch.getDistributorId(), batch.getRetailerId()).contains(me.getId())) {
            throw new AuthException(403, "无权查看该批次：只有本批次的生产商、被指定的分销商与零售商可以查看");
        }
        List<ChainTx> txs = chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getTraceNumber, traceNumber).isNotNull(ChainTx::getStage).orderByAsc(ChainTx::getId));
        List<TraceAssignmentLog> logs = assignmentLogMapper.selectList(new LambdaQueryWrapper<TraceAssignmentLog>()
                .eq(TraceAssignmentLog::getTraceNumber, traceNumber).orderByAsc(TraceAssignmentLog::getId));
        List<TraceCorrection> corrections = corrections(traceNumber);
        Set<Long> ids = new HashSet<>(Arrays.asList(batch.getProducerId(), batch.getDistributorId(), batch.getRetailerId()));
        logs.forEach(l -> ids.addAll(Arrays.asList(l.getFromUserId(), l.getToUserId(), l.getOperatorId())));
        Map<Long, UserAccount> users = users(ids);

        // 链上数据：读不到时仍返回链下状态，并给出 chainError，不拿链下副本冒充
        ChainTraceReader chain = reader(batch);
        List<String> actors = null;
        Map<TraceStage, Map<String, Object>> onChain = new EnumMap<>(TraceStage.class);
        String chainError = null;
        try {
            actors = chain.actors(traceNumber);
            if (actors != null) {
                for (TraceStage stage : TraceStage.values()) {
                    if (chain.actor(actors, stage) != null) {
                        onChain.put(stage, chain.stage(traceNumber, stage));
                    }
                }
            }
        } catch (BusinessException e) {
            chainError = e.getMessage();
        }

        List<Map<String, Object>> stages = new ArrayList<>();
        Map<String, Boolean> canCorrect = new LinkedHashMap<>();
        Map<TraceStage, String> statusOf = new EnumMap<>(TraceStage.class);
        for (TraceStage stage : TraceStage.values()) {
            ChainTx picked = pick(txs, stage);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stage", stage.name());
            s.put("code", stage.code());
            s.put("label", STAGE_LABELS.get(stage));
            s.putAll(txStatus(picked));
            s.put("txHash", picked == null ? null : picked.getTxHash());
            s.put("blockNumber", picked == null ? null : picked.getBlockNumber());
            s.put("errorReason", picked == null ? null : picked.getErrorReason());
            s.put("verifyResult", picked == null ? null : picked.getVerifyResult());
            s.put("updatedAt", picked == null ? null : picked.getUpdatedAt());
            s.put("canVerify", picked != null && (admin || picked.getSigner().equalsIgnoreCase(me.getChainAddress()))
                    && ChainTxState.valueOf(picked.getState()).inflight());
            String writer = chainError == null ? chain.actor(actors, stage) : null;
            s.put("onChain", chainError == null ? onChain.containsKey(stage) : null);
            s.put("writer", writer);
            s.put("writerIsMe", writer != null && writer.equalsIgnoreCase(me.getChainAddress()));
            s.put("data", onChain.get(stage));
            s.put("hasFile", TraceFields.fileField(stage).isPresent() && onChain.containsKey(stage));
            // 文件状态：是否已绑定、本地 IPFS 里是否还在（缺失时前端给出明确提示，不显示空图片）
            s.put("file", TraceFields.fileField(stage).isPresent() && onChain.containsKey(stage)
                    ? fileService.describe(traceNumber, stage, (String) onChain.get(stage).get(TraceFields.fileField(stage).get()), true)
                    : null);
            s.put("corrections", corrections.stream().filter(c -> c.getStage() == stage.code())
                    .map(c -> correctionView(c, stage, false)).collect(Collectors.toList()));
            stages.add(s);
            statusOf.put(stage, (String) s.get("status"));
            canCorrect.put(stage.name(), writer != null && writer.equalsIgnoreCase(me.getChainAddress()));
        }

        boolean chainOk = chainError == null;
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("canProduce", chainOk && me.getId().equals(batch.getProducerId())
                && !onChain.containsKey(TraceStage.PRODUCTION) && resubmittable(statusOf.get(TraceStage.PRODUCTION)));
        permissions.put("canDistribute", chainOk && me.getId().equals(batch.getDistributorId())
                && onChain.containsKey(TraceStage.PRODUCTION) && !onChain.containsKey(TraceStage.DISTRIBUTION)
                && resubmittable(statusOf.get(TraceStage.DISTRIBUTION)));
        permissions.put("canRetail", chainOk && me.getId().equals(batch.getRetailerId())
                && onChain.containsKey(TraceStage.DISTRIBUTION) && !onChain.containsKey(TraceStage.RETAIL)
                && resubmittable(statusOf.get(TraceStage.RETAIL)));
        permissions.put("canAssignDistributor", chainOk && me.getId().equals(batch.getProducerId())
                && !onChain.containsKey(TraceStage.DISTRIBUTION) && resubmittable(statusOf.get(TraceStage.DISTRIBUTION)));
        permissions.put("canAssignRetailer", !"V3".equals(version(batch)) && chainOk && batch.getDistributorId() != null && me.getId().equals(batch.getDistributorId())
                && !onChain.containsKey(TraceStage.RETAIL) && resubmittable(statusOf.get(TraceStage.RETAIL)));
        permissions.put("canCorrect", canCorrect);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceNumber", traceNumber);
        result.put("contractVersion", version(batch));
        result.put("contractAddress", batch.getContractAddress());
        List<String> designated = chainError == null && actors != null ? chain.designations(traceNumber) : null;
        result.put("chainDesignatedDistributor", designated == null ? null : designated.get(0));
        result.put("chainDesignatedRetailer", designated == null ? null : designated.get(1));
        result.put("productName", batch.getProductName());
        result.put("createdAt", batch.getCreatedAt());
        result.put("producer", party(users.get(batch.getProducerId())));
        result.put("distributor", party(users.get(batch.getDistributorId())));
        result.put("retailer", party(users.get(batch.getRetailerId())));
        result.put("chainError", chainError);
        result.put("stages", stages);
        result.put("assignments", logs.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", TraceStage.ofCode(l.getStage()).name());
            m.put("from", party(users.get(l.getFromUserId())));
            m.put("to", party(users.get(l.getToUserId())));
            UserAccount op = users.get(l.getOperatorId());
            m.put("operator", op == null ? null : op.getUsername());
            m.put("reason", l.getReason());
            m.put("createdAt", l.getCreatedAt());
            return m;
        }).collect(Collectors.toList()));
        result.put("permissions", permissions);
        return result;
    }

    private static boolean resubmittable(String status)
    {
        return NOT_STARTED.equals(status) || FAILED.equals(status) || RELEASED.equals(status);
    }

    // ---------------------------------------------------------------- 更正

    @Override
    public Map<String, Object> addCorrection(String traceNumber, CorrectionTo to)
    {
        UserAccount me = currentUser();
        TraceValidator v = TraceValidator.create();
        TraceStage stage = null;
        try {
            stage = TraceStage.valueOf(StrUtil.nullToEmpty(to.getStage()).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            v.add("stage", "必须是 PRODUCTION / DISTRIBUTION / RETAIL 之一");
        }
        String reason = StrUtil.trim(to.getReason());
        v.text("reason", reason, 500);
        Map<String, String> content = new LinkedHashMap<>();
        if (to.getContent() == null || to.getContent().isEmpty()) {
            v.add("content", "至少更正一个字段");
        } else if (stage != null) {
            for (Map.Entry<String, Object> e : to.getContent().entrySet()) {
                TraceFields.Field f = TraceFields.find(stage, e.getKey()).orElse(null);
                if (f == null) {
                    v.add("content." + e.getKey(), "不是" + STAGE_LABELS.get(stage) + "阶段的字段");
                    continue;
                }
                String value = e.getValue() == null ? null : StrUtil.trim(String.valueOf(e.getValue()));
                TraceValidator one = TraceValidator.create().field(f, value);
                if (one.hasError(f.name)) {
                    // 复用字段规则，但错误字段名带上 content. 前缀
                    try {
                        one.throwIfInvalid();
                    } catch (com.qhx.back.exception.ValidationException ve) {
                        ve.getErrors().forEach(err -> v.add("content." + err.get("field"), err.get("message")));
                    }
                    continue;
                }
                content.put(f.name, value);
            }
        }
        String json = JSONUtil.toJsonStr(content);
        if (json.length() > 2000) {
            v.add("content", "更正内容过长");
        }
        v.throwIfInvalid();

        TraceBatch batch = requireBatch(traceNumber);
        ChainTraceReader chain = reader(batch);
        String writer = chain.actor(chain.actors(traceNumber), stage);
        if (writer == null) {
            throw new BusinessException(409, STAGE_LABELS.get(stage) + "信息尚未上链，没有可更正的记录");
        }
        if (!writer.equalsIgnoreCase(me.getChainAddress())) {
            throw new AuthException(403, "只有该阶段的链上写入者本人可以提交更正");
        }

        TraceCorrection c = new TraceCorrection();
        c.setTraceNumber(batch.getTraceNumber());
        c.setStage(stage.code());
        c.setAuthorId(me.getId());
        c.setAuthorUsername(me.getUsername());
        c.setAuthorCompany(me.getCompanyName());
        c.setAuthorAddress(me.getChainAddress());
        c.setReason(reason);
        c.setContent(json);
        c.setCreatedAt(new Date());
        // 只追加：应用层不提供修改、删除更正的接口
        correctionMapper.insert(c);
        return correctionView(c, stage, false);
    }

    private List<TraceCorrection> corrections(String traceNumber)
    {
        return correctionMapper.selectList(new LambdaQueryWrapper<TraceCorrection>()
                .eq(TraceCorrection::getTraceNumber, traceNumber).orderByAsc(TraceCorrection::getId));
    }

    /** onlyPublic=true 时只保留公开字段，不含提交人用户名与地址 */
    private Map<String, Object> correctionView(TraceCorrection c, TraceStage stage, boolean onlyPublic)
    {
        JSONObject content = JSONUtil.parseObj(c.getContent());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (TraceFields.Field f : TraceFields.of(stage)) {
            if (content.containsKey(f.name) && (!onlyPublic || f.isPublic)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", f.name);
                m.put("label", f.label);
                m.put("value", content.getStr(f.name));
                fields.add(m);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", c.getId());
        view.put("stage", stage.name());
        view.put("fields", fields);
        view.put("reason", c.getReason());
        view.put("authorCompany", c.getAuthorCompany());
        if (!onlyPublic) {
            view.put("authorUsername", c.getAuthorUsername());
            view.put("authorAddress", c.getAuthorAddress());
        }
        view.put("createdAt", c.getCreatedAt());
        return view;
    }

    // ---------------------------------------------------------------- 可指定的交接对象

    @Override
    public List<Map<String, Object>> partners(String role)
    {
        UserAccount me = currentUser();
        UserRole wanted = UserRole.parse(role);
        UserRole mine = UserRole.parse(me.getRole());
        boolean allowed = mine == UserRole.ADMIN
                || (mine == UserRole.PRODUCER && wanted == UserRole.DISTRIBUTOR)
                || (mine == UserRole.DISTRIBUTOR && wanted == UserRole.RETAILER);
        if (!allowed) {
            throw new AuthException(403, "生产商只能查询分销商，分销商只能查询零售商");
        }
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getRole, wanted.name()).eq(UserAccount::getEnabled, true)
                        .orderByAsc(UserAccount::getUsername))
                .stream().map(this::party).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- 消费者公开视图

    @Override
    public Map<String, Object> publicDetail(String traceNumber)
    {
        TraceValidator.create().traceNumber("traceNumber", traceNumber).throwIfInvalid();
        // 读模型优先；还没有这一行（例如确认回调时链暂时读不到）才读一次链并补进读模型
        TraceReadModel row = readModelService.get(traceNumber);
        if (row == null) {
            row = readModelService.refresh(traceNumber);
        }
        if (row == null || row.getProductionData() == null) {
            throw new BusinessException(404, "未找到该溯源信息");
        }
        Map<TraceStage, Map<String, Object>> data = new EnumMap<>(TraceStage.class);
        data.put(TraceStage.PRODUCTION, parseData(row.getProductionData()));
        data.put(TraceStage.DISTRIBUTION, parseData(row.getDistributionData()));
        data.put(TraceStage.RETAIL, parseData(row.getRetailData()));

        List<ChainTx> txs = chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getTraceNumber, traceNumber)
                .eq(ChainTx::getState, ChainTxState.CONFIRMED.name())
                .orderByAsc(ChainTx::getId));
        List<TraceCorrection> corrections = corrections(traceNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceNumber", traceNumber);
        List<Map<String, Object>> stages = new ArrayList<>();
        for (TraceStage stage : TraceStage.values()) {
            Map<String, Object> d = data.get(stage);
            Map<String, Object> pub = new LinkedHashMap<>();
            if (d != null) {
                for (TraceFields.Field f : TraceFields.of(stage)) {
                    if (f.isPublic) {
                        pub.put(f.name, d.get(f.name));
                    }
                }
                pub.put("timestamp", d.get("timestamp"));
                String cid = TraceFields.fileField(stage).map(name -> (String) d.get(name)).orElse(null);
                pub.put("hasFile", StrUtil.isNotBlank(cid));
                if (StrUtil.isNotBlank(cid)) {
                    // 只给状态，不给 CID：AVAILABLE 可读 / MISSING 存储节点上已缺失 / NOT_BOUND 未绑定 / UNAVAILABLE 存储服务不可用
                    Map<String, Object> described = fileService.describe(traceNumber, stage, cid, true);
                    pub.put("fileState", publicFileState(described));
                    // 类型决定前端内联展示（图片）还是给下载链接（PDF）
                    pub.put("fileType", described.get("mimeType"));
                }
            }
            // 兼容原消费者页面的 producer / distributor / retailer 三段结构
            result.put(legacyKey(stage), pub);

            ChainTx confirmed = pick(txs, stage);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stage", stage.name());
            s.put("label", STAGE_LABELS.get(stage));
            s.put("recorded", d != null);
            s.put("txHash", d == null || confirmed == null ? null : confirmed.getTxHash());
            s.put("blockNumber", d == null || confirmed == null ? null : confirmed.getBlockNumber());
            s.put("corrections", corrections.stream()
                    .filter(c -> c.getStage() == stage.code())
                    .map(c -> correctionView(c, stage, true))
                    .filter(c -> !((List<?>) c.get("fields")).isEmpty())
                    .collect(Collectors.toList()));
            stages.add(s);
        }
        result.put("stages", stages);
        // 数据来源：读模型（由读链结果写入）；syncedAt 为最近一次内容变化的时间
        result.put("source", "READ_MODEL");
        result.put("syncedAt", row.getSyncedAt());
        return result;
    }

    private static Map<String, Object> parseData(String json)
    {
        return json == null ? null : JSONUtil.parseObj(json);
    }

    private static String publicFileState(Map<String, Object> described)
    {
        if (!"BOUND".equals(described.get("state"))) {
            return "NOT_BOUND";
        }
        Object available = described.get("available");
        return Boolean.TRUE.equals(available) ? "AVAILABLE" : Boolean.FALSE.equals(available) ? "MISSING" : "UNAVAILABLE";
    }

    @Override
    public PageResult<Map<String, Object>> searchPublic(String keyword, PageQuery page)
    {
        return readModelService.searchPublic(keyword, page);
    }

    @Override
    public FileService.PublicFile publicFile(String traceNumber, String stageName)
    {
        TraceValidator.create().traceNumber("traceNumber", traceNumber).throwIfInvalid();
        TraceStage stage = parsePublicFileStage(stageName);
        String field = TraceFields.fileField(stage).orElseThrow(() -> new BusinessException(400, "该阶段没有文件"));
        // CID 只从链上该溯源号该阶段的字段读取，调用方无法指定任意 CID；还必须与本系统已绑定（BOUND）的记录一致
        Map<String, Object> data = reader(findBatch(traceNumber)).stage(traceNumber, stage);
        String cid = data == null ? null : (String) data.get(field);
        if (StrUtil.isBlank(cid)) {
            throw new BusinessException(404, "该溯源号的" + STAGE_LABELS.get(stage) + "阶段没有登记文件",
                    Collections.singletonMap("errorCode", "FILE_NOT_BOUND"));
        }
        return fileService.openPublic(traceNumber, stage, cid);
    }

    private static TraceStage parsePublicFileStage(String stageName)
    {
        String s = StrUtil.nullToEmpty(stageName).trim().toUpperCase();
        if (s.equals("PRODUCTION") || s.equals("DISTRIBUTION")) {
            return TraceStage.valueOf(s);
        }
        throw new BusinessException(400, "stage 只能是 production（生产认证）或 distribution（质检报告）");
    }

    private static String legacyKey(TraceStage stage)
    {
        switch (stage) {
            case PRODUCTION:
                return "producer";
            case DISTRIBUTION:
                return "distributor";
            default:
                return "retailer";
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 某阶段用于展示的交易记录：有成功记录取最新的成功记录，否则取最新一条 */
    private static ChainTx pick(List<ChainTx> txs, TraceStage stage)
    {
        List<ChainTx> own = txs.stream().filter(t -> Objects.equals(t.getStage(), stage.code())).collect(Collectors.toList());
        return own.stream().filter(t -> ChainTxState.CONFIRMED.name().equals(t.getState()))
                .max(Comparator.comparing(ChainTx::getId))
                .orElseGet(() -> own.stream().max(Comparator.comparing(ChainTx::getId)).orElse(null));
    }

    private static Map<String, Object> txStatus(ChainTx tx)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (tx == null) {
            m.put("status", NOT_STARTED);
            m.put("txId", null);
            return m;
        }
        String status;
        switch (ChainTxState.valueOf(tx.getState())) {
            case CONFIRMED:
                status = CONFIRMED;
                break;
            case FAILED:
                status = FAILED;
                break;
            case UNKNOWN:
                status = tx.getInflightKey() == null ? RELEASED : PENDING;
                break;
            default:
                status = PENDING;
        }
        m.put("status", status);
        m.put("txId", tx.getId());
        m.put("txState", tx.getState());
        return m;
    }

    private Map<String, Object> party(UserAccount u)
    {
        if (u == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("companyName", u.getCompanyName());
        return m;
    }

    private Map<Long, UserAccount> users(Collection<Long> ids)
    {
        Set<Long> nonNull = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (nonNull.isEmpty()) {
            return new HashMap<>();
        }
        return userAccountMapper.selectBatchIds(nonNull).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    /** 按用户名找启用中的指定角色账号；找不到时记一条字段错误并返回 null */
    private UserAccount resolvePartner(TraceValidator v, String field, String username, UserRole role)
    {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        UserAccount u = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username.trim())
                .eq(UserAccount::getRole, role.name())
                .eq(UserAccount::getEnabled, true));
        if (u == null) {
            v.add(field, "账号不存在、未启用，或不是" + role.getDesc());
        }
        return u;
    }

    private TraceBatch findBatch(String traceNumber)
    {
        return batchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>().eq(TraceBatch::getTraceNumber, traceNumber));
    }

    private TraceBatch requireBatch(String traceNumber)
    {
        if (!TraceValidator.isTraceNumber(traceNumber)) {
            TraceValidator.create().traceNumber("traceNumber", traceNumber).throwIfInvalid();
        }
        TraceBatch batch = findBatch(traceNumber);
        if (batch == null) {
            throw new BusinessException(404, "该溯源号未在本系统建档");
        }
        return batch;
    }

    private UserAccount currentUser()
    {
        UserAccount user = UserContext.getUser();
        if (user == null) {
            throw new AuthException(401, "未登录");
        }
        return user;
    }

    private String version(TraceBatch batch)
    {
        return batch == null || batch.getContractVersion() == null ? "V2" : batch.getContractVersion();
    }

    private ChainTraceReader reader(TraceBatch batch)
    {
        if (batch == null) return new ChainTraceReader(weBaseClient);
        String configured = "V3".equals(version(batch)) ? v3Address : v2Address;
        if (batch.getContractAddress() == null || !batch.getContractAddress().equalsIgnoreCase(configured)) {
            throw new BusinessException(409, "批次绑定的合约地址与当前配置不一致，拒绝读写链上数据");
        }
        return new ChainTraceReader(weBaseClient, version(batch));
    }
}
