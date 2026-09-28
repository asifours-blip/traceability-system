package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 批次归属与交接对象（链下，后端规则）。见 docs/business-flow.md。
 */
@Data
@TableName("trace_batch")
public class TraceBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceNumber;
    // 仅用于列表展示的链下副本，以链上数据为准
    private String productName;
    private Long producerId;
    // 当前指定的分销商 / 零售商；只有被指定者能写对应阶段
    private Long distributorId;
    private Long retailerId;
    private String contractVersion;
    private String contractAddress;
    private Date createdAt;
    private Date updatedAt;
}
