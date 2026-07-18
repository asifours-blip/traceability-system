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
    private String productionCert;
    private String productTime;
}
