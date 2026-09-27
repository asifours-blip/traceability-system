package com.qhx.back.model.to;

import lombok.Data;
@Data
public class ProducerTo
{
    private String traceNumber;
    private String companyName;
    private String productName;
    private String productionLocation;
    private String variety;
    private String productionBatch;
    // 生产认证图片的 IPFS CID
    private String productionCert;
    // yyyy-MM-dd
    private String productTime;
    // 下游分销商的用户名（后端规则：只有被指定的分销商能写分销阶段）
    private String distributorUsername;
}
