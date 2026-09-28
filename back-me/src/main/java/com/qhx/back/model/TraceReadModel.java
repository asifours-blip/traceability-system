package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 溯源读模型的一行：全部字段来自读链结果（getStageActors + 各阶段读函数），不取自请求体。见 docs/read-model.md。
 */
@Data
@TableName("trace_read_model")
public class TraceReadModel {
    @TableId(type = IdType.INPUT)
    private String traceNumber;
    private Integer listIndex;
    private Integer stageReached;
    private String productName;
    private String producerCompany;
    private String productionLocation;
    private String variety;
    private String productTime;
    private Long productionTs;
    private Long distributionTs;
    private Long retailTs;
    private String producerAddress;
    private String distributorAddress;
    private String retailerAddress;
    private String productionData;
    private String distributionData;
    private String retailData;
    private String productionCid;
    private String distributionCid;
    private String claimStatus;
    private String claimNote;
    private Date syncedAt;
}
