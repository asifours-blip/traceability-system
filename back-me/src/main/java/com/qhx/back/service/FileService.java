package com.qhx.back.service;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.UserAccount;
import com.qhx.back.trace.TraceValidator;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Date;
import java.util.Map;

/**
 * 文件存储：上传（校验、流式写 IPFS、核对）、与阶段交易的绑定、孤儿清理、公开读取。
 * 状态机见 docs/files.md：UPLOADED → BOUND（阶段交易 CONFIRMED 后）/ ORPHANED（超期未绑定，取消 pin）。
 */
public interface FileService
{
    /** 当前账号上传一个文件；成功返回文件记录（状态 UPLOADED） */
    Map<String, Object> upload(MultipartFile file);

    /**
     * 提交阶段交易前校验文件字段：CID 必须是当前账号上传、仍可使用（UPLOADED 且未被其他未决交易占用）的文件。
     * 不合格时往 v 里记一条字段错误，不抛异常。
     */
    void checkUsable(TraceValidator v, String field, String cid, UserAccount me, String traceNumber, TraceStage stage);

    /** 发交易之前占用文件（记录溯源号与阶段）：交易确认后据此绑定，未决期间不会被当成孤儿清理 */
    void claim(String cid, UserAccount me, String traceNumber, TraceStage stage);

    /**
     * 阶段交易确认后绑定文件。只认数据库里状态为 CONFIRMED 的交易；chainCid 必须是读链得到的该阶段 CID。
     * FAILED / UNKNOWN / 未决的交易一律不绑定。
     */
    BindOutcome bindConfirmed(ChainTx tx, String chainCid);

    /**
     * 重建读模型时按链上数据补齐绑定：已有占用且交易已确认的直接绑定；本系统没有记录的旧文件从本地 IPFS 读出、
     * 按同样的类型与大小规则核对后登记为 BOUND（bind_source=REBUILD）。
     *
     * @param writer 链上该阶段写入者对应的账号，可为 null
     */
    BindOutcome reconcile(String traceNumber, TraceStage stage, String chainCid, UserAccount writer);

    /** 把超过期限仍未绑定的 UPLOADED 标为 ORPHANED 并取消 pin；返回统计 */
    Map<String, Object> cleanupOrphans(Date now);

    /**
     * 公开读取：只允许状态为 BOUND、且 CID 与链上该阶段一致的文件。
     * 未绑定 → 404 FILE_NOT_BOUND；本地 IPFS 里取不到 → 410 FILE_MISSING；IPFS 不可用 → 503 IPFS_UNAVAILABLE。
     */
    PublicFile openPublic(String traceNumber, TraceStage stage, String chainCid);

    /** 某阶段文件的状态说明（详情页用）；probe=true 时顺带检查本地 IPFS 是否还有该文件 */
    Map<String, Object> describe(String traceNumber, TraceStage stage, String chainCid, boolean probe);

    enum BindOutcome
    {
        /** 该阶段链上没有文件字段或为空 */
        NO_FILE,
        /** 交易不是 CONFIRMED，不绑定 */
        NOT_CONFIRMED,
        /** 已经绑定过（幂等） */
        ALREADY_BOUND,
        BOUND,
        /** 从本地 IPFS 导入的旧文件 */
        IMPORTED,
        /** 找不到可绑定的上传记录 */
        NO_CANDIDATE,
        /** 该阶段已绑定了另一个 CID（链上 CID 与绑定不一致） */
        CONFLICT,
        /** 本地 IPFS 里没有该 CID */
        MISSING,
        /** 内容不符合上传规则（类型 / 大小），不登记 */
        REJECTED,
        /** IPFS 不可用，未能判断 */
        UNAVAILABLE
    }

    final class PublicFile
    {
        public final InputStream stream;
        public final long size;
        public final String mimeType;
        public final String fileName;
        public final boolean image;

        public PublicFile(InputStream stream, long size, String mimeType, String fileName, boolean image)
        {
            this.stream = stream;
            this.size = size;
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.image = image;
        }
    }
}
