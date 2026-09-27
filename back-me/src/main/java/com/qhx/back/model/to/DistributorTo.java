package com.qhx.back.model.to;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DistributorTo
{
    private String traceNumber;
    private String companyName;
    private String storageCondition;
    private String transportMethod;
    private String distributeBatch;
    private String storageLocation;
    // 用 BigDecimal 接收：Jackson 会把 10.5 静默截断成 Long 10，这里先原样收下再校验「正整数」
    private BigDecimal distributePrice;
    private BigDecimal distributeQuantity;
    // 质检报告图片的 IPFS CID
    private String inspectionReport;
    // 下游零售商的用户名（后端规则：只有被指定的零售商能写零售阶段）
    private String retailerUsername;
}
