package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 上传到 IPFS 的文件。状态机见 docs/files.md：UPLOADED → BOUND（阶段交易 CONFIRMED 后）/ ORPHANED（超期未绑定）。
 */
@Data
@TableName("file_object")
public class FileObject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cid;
    private String sha256;
    private Long sizeBytes;
    private String mimeType;
    private String fileName;
    private Long uploaderId;
    private String status;
    // 提交阶段交易时占用的溯源号与阶段
    private String claimTraceNumber;
    private Integer claimStage;
    // 绑定结果
    private String traceNumber;
    private Integer stage;
    private String boundKey;
    private Long chainTxId;
    private String bindSource;
    private Date boundAt;
    private Date orphanedAt;
    private Boolean unpinned;
    private String unpinNote;
    private Date createdAt;
    private Date updatedAt;
}
