package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.chain.ChainTxState;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.context.UserContext;
import com.qhx.back.exception.AuthException;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.file.FileNames;
import com.qhx.back.file.FileRejectedException;
import com.qhx.back.file.FileType;
import com.qhx.back.file.IpfsException;
import com.qhx.back.file.KuboClient;
import com.qhx.back.file.UploadPipeline;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.FileObjectMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.FileObject;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.FileService;
import com.qhx.back.trace.TraceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class FileServiceImpl implements FileService
{
    static final String UPLOADED = "UPLOADED";
    static final String BOUND = "BOUND";
    static final String ORPHANED = "ORPHANED";

    @Autowired
    private FileObjectMapper fileMapper;
    @Autowired
    private ChainTxMapper chainTxMapper;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private KuboClient kubo;
    @Autowired
    private UploadPipeline pipeline;

    /** UPLOADED 超过这个时间仍未被确认的交易引用，就标为 ORPHANED */
    @Value("${file.orphan.ttl-hours:24}")
    private long orphanTtlHours;

    // ---------------------------------------------------------------- 上传

    @Override
    public Map<String, Object> upload(MultipartFile file)
    {
        UserAccount me = UserContext.getUser();
        if (me == null) {
            throw new AuthException(401, "未登录");
        }
        if (file == null || file.isEmpty()) {
            throw new FileRejectedException(400, "FILE_EMPTY", "文件为空");
        }
        // 已落盘的大小先挡一道，超限的不再读；流式读取时还会再计数一次
        if (file.getSize() > pipeline.getMaxBytes()) {
            throw new FileRejectedException(413, "FILE_TOO_LARGE", "文件超过大小上限 " + pipeline.getMaxBytes() + " 字节");
        }
        UploadPipeline.Stored stored;
        try {
            stored = pipeline.store(file::getInputStream, file.getOriginalFilename());
        } catch (FileRejectedException e) {
            if (e.getCid() != null) {
                // 已写入 IPFS 但核对失败：没有别的记录引用这个 CID 时取消 pin，不留下无主内容
                releasePin(e.getCid(), null);
            }
            throw e;
        }
        FileObject row = new FileObject();
        row.setCid(stored.cid);
        row.setSha256(stored.sha256);
        row.setSizeBytes(stored.size);
        row.setMimeType(stored.type.mime);
        row.setFileName(stored.fileName);
        row.setUploaderId(me.getId());
        row.setStatus(UPLOADED);
        row.setUnpinned(false);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        fileMapper.insert(row);
        log.info("文件已上传：#{} cid={} sha256={} size={} uploader={}", row.getId(), row.getCid(), row.getSha256(),
                row.getSizeBytes(), me.getUsername());
        return view(row);
    }

    static Map<String, Object> view(FileObject row)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("cid", row.getCid());
        // 兼容原接口的返回字段
        m.put("hash", row.getCid());
        m.put("sha256", row.getSha256());
        m.put("size", row.getSizeBytes());
        m.put("mimeType", row.getMimeType());
        m.put("fileName", row.getFileName());
        m.put("status", row.getStatus());
        m.put("createdAt", row.getCreatedAt());
        return m;
    }

    // ---------------------------------------------------------------- 占用与绑定

    @Override
    public void checkUsable(TraceValidator v, String field, String cid, UserAccount me, String traceNumber, TraceStage stage)
    {
        if (StrUtil.isBlank(cid) || v.hasError(field)) {
            return;
        }
        StringBuilder reason = new StringBuilder();
        if (candidate(cid.trim(), me.getId(), traceNumber, stage, reason) == null) {
            v.add(field, reason.toString());
        }
    }

    @Override
    public void claim(String cid, UserAccount me, String traceNumber, TraceStage stage)
    {
        StringBuilder reason = new StringBuilder();
        FileObject row = candidate(cid.trim(), me.getId(), traceNumber, stage, reason);
        if (row == null) {
            throw new BusinessException(409, reason.toString());
        }
        int updated = fileMapper.update(null, new LambdaUpdateWrapper<FileObject>()
                .set(FileObject::getClaimTraceNumber, traceNumber)
                .set(FileObject::getClaimStage, stage.code())
                .set(FileObject::getUpdatedAt, new Date())
                .eq(FileObject::getId, row.getId())
                .eq(FileObject::getStatus, UPLOADED));
        if (updated == 0) {
            throw new BusinessException(409, "文件状态已变化（可能刚被清理），请重新上传");
        }
    }

    /**
     * 当前账号可用于 (溯源号, 阶段) 的上传记录：优先已占用同一阶段的，其次未被占用或占用已失效的；都没有时在 reason 里说明原因。
     * 同一内容可以上传多次（每次一条记录），但一条记录只能绑定一个阶段。
     */
    private FileObject candidate(String cid, Long uploaderId, String traceNumber, TraceStage stage, StringBuilder reason)
    {
        List<FileObject> rows = fileMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getCid, cid).eq(FileObject::getUploaderId, uploaderId).orderByDesc(FileObject::getId));
        for (FileObject r : rows) {
            if (UPLOADED.equals(r.getStatus()) && traceNumber.equals(r.getClaimTraceNumber())
                    && Objects.equals(stage.code(), r.getClaimStage())) {
                return r;
            }
        }
        FileObject busy = null;
        for (FileObject r : rows) {
            if (UPLOADED.equals(r.getStatus())) {
                if (r.getClaimTraceNumber() == null || !claimStillHeld(r)) {
                    return r;
                }
                busy = r;
            }
        }
        if (rows.isEmpty()) {
            reason.append("文件未通过本系统上传，或不是当前账号上传的，请先上传");
        } else if (busy != null) {
            reason.append("该文件正被溯源号 ").append(busy.getClaimTraceNumber())
                    .append(" 的交易占用（待确认或已确认），如需复用请重新上传同一文件");
        } else if (rows.stream().anyMatch(r -> BOUND.equals(r.getStatus()))) {
            FileObject bound = rows.stream().filter(r -> BOUND.equals(r.getStatus())).findFirst().get();
            reason.append("该文件已绑定到溯源号 ").append(bound.getTraceNumber()).append("，不能重复使用；如需复用请重新上传同一文件");
        } else {
            reason.append("该文件超过 ").append(orphanTtlHours).append(" 小时未被使用，已被清理，请重新上传");
        }
        return null;
    }

    /** 占用是否仍有效：被占用的阶段有未决交易，或已有确认的交易（等待绑定） */
    private boolean claimStillHeld(FileObject r)
    {
        TraceStage stage = TraceStage.ofCode(r.getClaimStage());
        if (stage == null) {
            return false;
        }
        String bizKey = "trace:" + r.getClaimTraceNumber() + ":" + stage.name();
        Long n = chainTxMapper.selectCount(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getBizKey, bizKey)
                .and(w -> w.isNotNull(ChainTx::getInflightKey).or().eq(ChainTx::getState, ChainTxState.CONFIRMED.name())));
        return n != null && n > 0;
    }

    @Override
    public BindOutcome bindConfirmed(ChainTx tx, String chainCid)
    {
        // 以数据库里的最新状态为准，不信任调用方传入的对象
        ChainTx current = tx == null || tx.getId() == null ? null : chainTxMapper.selectById(tx.getId());
        if (current == null || !ChainTxState.CONFIRMED.name().equals(current.getState())) {
            return BindOutcome.NOT_CONFIRMED;
        }
        TraceStage stage = TraceStage.ofCode(current.getStage());
        if (stage == null || StrUtil.isBlank(chainCid)) {
            return BindOutcome.NO_FILE;
        }
        String tn = current.getTraceNumber();
        BindOutcome existing = existingBinding(tn, stage, chainCid);
        if (existing != null) {
            return existing;
        }
        UserAccount signer = accountByAddress(current.getSigner());
        if (signer == null) {
            log.warn("交易 #{} 已确认，但签名地址 {} 没有对应账号，无法绑定文件 {}", current.getId(), current.getSigner(), chainCid);
            return BindOutcome.NO_CANDIDATE;
        }
        FileObject row = fileMapper.selectList(new LambdaQueryWrapper<FileObject>()
                        .eq(FileObject::getCid, chainCid).eq(FileObject::getUploaderId, signer.getId())
                        .eq(FileObject::getStatus, UPLOADED)
                        .eq(FileObject::getClaimTraceNumber, tn).eq(FileObject::getClaimStage, stage.code())
                        .orderByDesc(FileObject::getId))
                .stream().findFirst().orElse(null);
        if (row == null) {
            log.warn("交易 #{} 已确认，但找不到 {} 为 {} {} 占用的上传记录（CID {}）", current.getId(), signer.getUsername(), tn, stage, chainCid);
            return BindOutcome.NO_CANDIDATE;
        }
        return markBound(row.getId(), tn, stage, current.getId(), "TX_CONFIRMED") ? BindOutcome.BOUND : existingOrConflict(tn, stage, chainCid);
    }

    @Override
    public BindOutcome reconcile(String traceNumber, TraceStage stage, String chainCid, UserAccount writer)
    {
        if (StrUtil.isBlank(chainCid)) {
            return BindOutcome.NO_FILE;
        }
        BindOutcome existing = existingBinding(traceNumber, stage, chainCid);
        if (existing != null) {
            return existing;
        }
        // 1. 本系统上传、已占用、交易已确认但当时没绑上（例如确认回调时链暂时读不到）
        List<FileObject> claimed = fileMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getCid, chainCid).eq(FileObject::getStatus, UPLOADED)
                .eq(FileObject::getClaimTraceNumber, traceNumber).eq(FileObject::getClaimStage, stage.code())
                .orderByDesc(FileObject::getId));
        String bizKey = "trace:" + traceNumber + ":" + stage.name();
        ChainTx confirmed = chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                        .eq(ChainTx::getBizKey, bizKey).eq(ChainTx::getState, ChainTxState.CONFIRMED.name())
                        .orderByDesc(ChainTx::getId))
                .stream().findFirst().orElse(null);
        if (confirmed != null) {
            UserAccount signer = accountByAddress(confirmed.getSigner());
            for (FileObject r : claimed) {
                if (signer != null && signer.getId().equals(r.getUploaderId())) {
                    return markBound(r.getId(), traceNumber, stage, confirmed.getId(), "TX_CONFIRMED")
                            ? BindOutcome.BOUND : existingOrConflict(traceNumber, stage, chainCid);
                }
            }
        }
        // 2. 本系统没有记录（功能上线前写入的旧批次）：从本地 IPFS 读出核对后登记
        return importFromIpfs(traceNumber, stage, chainCid, writer);
    }

    private BindOutcome importFromIpfs(String traceNumber, TraceStage stage, String cid, UserAccount writer)
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] head = new byte[FileType.HEAD_BYTES];
        int headLen = 0;
        long size = 0;
        try (InputStream in = kubo.cat(cid)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (headLen < head.length) {
                    int take = Math.min(n, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, take);
                    headLen += take;
                }
                size += n;
                if (size > pipeline.getMaxBytes()) {
                    log.warn("旧文件 {} 超过大小上限，不登记", cid);
                    return BindOutcome.REJECTED;
                }
                digest.update(buf, 0, n);
            }
        } catch (IpfsException e) {
            if (e.getKind() == IpfsException.Kind.MISSING) {
                return BindOutcome.MISSING;
            }
            if (e.getKind() == IpfsException.Kind.ERROR) {
                // 节点正常但拒绝了这个 CID（例如旧数据里的 "QmCert" 不是合法 CID）：内容不可能取到，按不合规处理
                log.warn("旧文件 CID {} 无效：{}", cid, e.getMessage());
                return BindOutcome.REJECTED;
            }
            log.warn("导入旧文件 {} 时 IPFS 不可用：{}", cid, e.getMessage());
            return BindOutcome.UNAVAILABLE;
        } catch (IOException e) {
            log.warn("导入旧文件 {} 时读取中断：{}", cid, e.getMessage());
            return BindOutcome.UNAVAILABLE;
        }
        FileType type = size == 0 ? null : FileType.sniff(head, headLen).orElse(null);
        if (type == null) {
            log.warn("旧文件 {} 为空或类型不在白名单内，不登记", cid);
            return BindOutcome.REJECTED;
        }
        try {
            // 登记为 BOUND 的内容必须 pin 住，否则会被 GC 回收
            kubo.pin(cid);
        } catch (IpfsException e) {
            return e.getKind() == IpfsException.Kind.MISSING ? BindOutcome.MISSING : BindOutcome.UNAVAILABLE;
        }
        String label = stage == TraceStage.PRODUCTION ? "生产认证" : "质检报告";
        FileObject row = new FileObject();
        row.setCid(cid);
        row.setSha256(hex(digest.digest()));
        row.setSizeBytes(size);
        row.setMimeType(type.mime);
        row.setFileName(FileNames.sanitize(label + "-" + traceNumber + "." + type.defaultExtension()));
        row.setUploaderId(writer == null ? null : writer.getId());
        row.setStatus(BOUND);
        row.setTraceNumber(traceNumber);
        row.setStage(stage.code());
        row.setBoundKey(boundKey(traceNumber, stage));
        row.setBindSource("REBUILD");
        row.setBoundAt(new Date());
        row.setUnpinned(false);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        try {
            fileMapper.insert(row);
        } catch (DuplicateKeyException e) {
            return existingOrConflict(traceNumber, stage, cid);
        }
        return BindOutcome.IMPORTED;
    }

    /** 该阶段已有绑定时：同一 CID 为 ALREADY_BOUND，不同 CID 为 CONFLICT；没有绑定返回 null */
    private BindOutcome existingBinding(String traceNumber, TraceStage stage, String chainCid)
    {
        FileObject bound = boundRow(traceNumber, stage);
        if (bound == null) {
            return null;
        }
        if (bound.getCid().equals(chainCid)) {
            return BindOutcome.ALREADY_BOUND;
        }
        log.error("{} {} 已绑定 CID {}，与链上 {} 不一致", traceNumber, stage, bound.getCid(), chainCid);
        return BindOutcome.CONFLICT;
    }

    private BindOutcome existingOrConflict(String traceNumber, TraceStage stage, String chainCid)
    {
        BindOutcome existing = existingBinding(traceNumber, stage, chainCid);
        return existing == null ? BindOutcome.NO_CANDIDATE : existing;
    }

    /** 条件更新：只有仍是 UPLOADED 的记录能变成 BOUND；bound_key 唯一，保证每个阶段最多一个已绑定文件 */
    private boolean markBound(Long id, String traceNumber, TraceStage stage, Long txId, String source)
    {
        try {
            return fileMapper.update(null, new LambdaUpdateWrapper<FileObject>()
                    .set(FileObject::getStatus, BOUND)
                    .set(FileObject::getTraceNumber, traceNumber)
                    .set(FileObject::getStage, stage.code())
                    .set(FileObject::getBoundKey, boundKey(traceNumber, stage))
                    .set(FileObject::getChainTxId, txId)
                    .set(FileObject::getBindSource, source)
                    .set(FileObject::getBoundAt, new Date())
                    .set(FileObject::getUpdatedAt, new Date())
                    .eq(FileObject::getId, id)
                    .eq(FileObject::getStatus, UPLOADED)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private FileObject boundRow(String traceNumber, TraceStage stage)
    {
        return fileMapper.selectOne(new LambdaQueryWrapper<FileObject>().eq(FileObject::getBoundKey, boundKey(traceNumber, stage)));
    }

    private static String boundKey(String traceNumber, TraceStage stage)
    {
        return traceNumber + ":" + stage.code();
    }

    // ---------------------------------------------------------------- 孤儿清理

    @Override
    public Map<String, Object> cleanupOrphans(Date now)
    {
        Date deadline = new Date(now.getTime() - orphanTtlHours * 3600_000L);
        List<FileObject> expired = fileMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getStatus, UPLOADED).lt(FileObject::getCreatedAt, deadline).orderByAsc(FileObject::getId));
        int orphaned = 0;
        int protectedByTx = 0;
        for (FileObject r : expired) {
            // 被未决或已确认的交易占用：交易还可能确认（或已确认待绑定），不能清理
            if (r.getClaimTraceNumber() != null && claimStillHeld(r)) {
                protectedByTx++;
                continue;
            }
            int updated = fileMapper.update(null, new LambdaUpdateWrapper<FileObject>()
                    .set(FileObject::getStatus, ORPHANED)
                    .set(FileObject::getOrphanedAt, now)
                    .set(FileObject::getUpdatedAt, now)
                    .eq(FileObject::getId, r.getId())
                    .eq(FileObject::getStatus, UPLOADED));
            if (updated == 1) {
                orphaned++;
            }
        }
        // 取消 pin：包括本轮新标记的，以及以前失败待重试的
        int unpinned = 0;
        int kept = 0;
        int failed = 0;
        for (FileObject r : fileMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getStatus, ORPHANED).eq(FileObject::getUnpinned, false))) {
            String result = releasePin(r.getCid(), r.getId());
            if ("UNPINNED".equals(result)) {
                unpinned++;
            } else if ("KEPT".equals(result)) {
                kept++;
            } else {
                failed++;
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("deadline", deadline);
        report.put("orphaned", orphaned);
        report.put("protectedByTx", protectedByTx);
        report.put("unpinned", unpinned);
        report.put("keptSharedCid", kept);
        report.put("unpinFailed", failed);
        if (orphaned > 0 || failed > 0) {
            log.info("孤儿文件清理：{}", report);
        }
        return report;
    }

    /**
     * 没有其他 UPLOADED / BOUND 记录引用同一 CID 时取消 pin（同一内容可能被别人也上传过）。
     * rowId 为 null 时只处理 IPFS，不写记录。返回 UNPINNED / KEPT / FAILED。
     */
    private String releasePin(String cid, Long rowId)
    {
        LambdaQueryWrapper<FileObject> others = new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getCid, cid).in(FileObject::getStatus, Arrays.asList(UPLOADED, BOUND));
        if (rowId != null) {
            others.ne(FileObject::getId, rowId);
        }
        Long refs = fileMapper.selectCount(others);
        String result;
        String note;
        if (refs != null && refs > 0) {
            result = "KEPT";
            note = "同一 CID 仍被 " + refs + " 条记录引用，保留 pin";
        } else {
            try {
                kubo.unpin(cid);
                result = "UNPINNED";
                note = "已取消 pin，内容在下次 repo gc 时回收";
            } catch (IpfsException e) {
                result = "FAILED";
                note = StrUtil.maxLength("取消 pin 失败，下轮重试：" + e.getMessage(), 490);
                log.warn("取消 pin {} 失败：{}", cid, e.getMessage());
            }
        }
        if (rowId != null) {
            fileMapper.update(null, new LambdaUpdateWrapper<FileObject>()
                    .set(FileObject::getUnpinned, !"FAILED".equals(result))
                    .set(FileObject::getUnpinNote, note)
                    .set(FileObject::getUpdatedAt, new Date())
                    .eq(FileObject::getId, rowId));
        }
        return result;
    }

    // ---------------------------------------------------------------- 读取

    @Override
    public PublicFile openPublic(String traceNumber, TraceStage stage, String chainCid)
    {
        FileObject bound = StrUtil.isBlank(chainCid) ? null : boundRow(traceNumber, stage);
        if (bound == null || !bound.getCid().equals(chainCid)) {
            throw new BusinessException(404, "该阶段的文件尚未绑定（对应交易未确认，或文件未经本系统登记），不能公开读取",
                    Collections.singletonMap("errorCode", "FILE_NOT_BOUND"));
        }
        InputStream stream;
        try {
            stream = kubo.cat(bound.getCid());
        } catch (IpfsException e) {
            if (e.getKind() == IpfsException.Kind.MISSING) {
                log.error("已绑定的文件在本地 IPFS 中缺失：{} {} cid={}", traceNumber, stage, bound.getCid());
                throw new BusinessException(410, "文件缺失：链上登记了该文件，但存储节点上已找不到内容，请联系管理员恢复",
                        Collections.singletonMap("errorCode", "FILE_MISSING"));
            }
            throw new BusinessException(503, "文件存储服务暂时不可用，请稍后重试",
                    Collections.singletonMap("errorCode", "IPFS_UNAVAILABLE"));
        }
        FileType type = FileType.ofMime(bound.getMimeType()).orElse(null);
        return new PublicFile(stream, bound.getSizeBytes(),
                type == null ? "application/octet-stream" : type.mime, bound.getFileName(), type != null && type.image);
    }

    @Override
    public Map<String, Object> describe(String traceNumber, TraceStage stage, String chainCid, boolean probe)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        if (StrUtil.isBlank(chainCid)) {
            m.put("state", "NONE");
            return m;
        }
        FileObject bound = boundRow(traceNumber, stage);
        if (bound != null && bound.getCid().equals(chainCid)) {
            m.put("state", BOUND);
            m.put("fileName", bound.getFileName());
            m.put("mimeType", bound.getMimeType());
            m.put("size", bound.getSizeBytes());
            m.put("sha256", bound.getSha256());
            m.put("bindSource", bound.getBindSource());
            m.put("boundAt", bound.getBoundAt());
            if (probe) {
                try {
                    boolean has = kubo.has(chainCid);
                    m.put("available", has);
                    if (!has) {
                        m.put("errorCode", "FILE_MISSING");
                        m.put("message", "链上登记了该文件，但存储节点上已找不到内容");
                    }
                } catch (IpfsException e) {
                    m.put("available", null);
                    m.put("errorCode", "IPFS_UNAVAILABLE");
                    m.put("message", "文件存储服务暂时不可用，无法确认文件是否存在");
                }
            }
            return m;
        }
        if (bound != null) {
            m.put("state", "CONFLICT");
            m.put("message", "本系统绑定的文件与链上 CID 不一致，不提供公开读取");
            return m;
        }
        boolean pending = fileMapper.selectCount(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getCid, chainCid).eq(FileObject::getStatus, UPLOADED)
                .eq(FileObject::getClaimTraceNumber, traceNumber).eq(FileObject::getClaimStage, stage.code())) > 0;
        m.put("state", "NOT_BOUND");
        m.put("message", pending ? "文件已上传，等待对应交易确认后绑定" : "文件未经本系统登记（可由管理员重建读模型时从 IPFS 导入）");
        return m;
    }

    // ---------------------------------------------------------------- 工具

    private UserAccount accountByAddress(String address)
    {
        if (StrUtil.isBlank(address)) {
            return null;
        }
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>()
                        .apply("LOWER(chain_address) = {0}", address.trim().toLowerCase()))
                .stream().findFirst().orElse(null);
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
