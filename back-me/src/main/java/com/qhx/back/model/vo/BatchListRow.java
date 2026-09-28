package com.qhx.back.model.vo;

import lombok.Data;

import java.util.Date;

/** 批次列表的一行：trace_batch（归属）左连读模型（链上进度） */
@Data
public class BatchListRow {
    private Long id;
    private String traceNumber;
    private String productName;
    private Long producerId;
    private Long distributorId;
    private Long retailerId;
    private Date createdAt;
    /** 读模型里链上已写入到的阶段；读模型还没有这行时为 null（视为 0） */
    private Integer stageReached;
}
