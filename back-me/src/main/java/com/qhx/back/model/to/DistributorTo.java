package com.qhx.back.model.to;

import lombok.Data;
@Data
public class DistributorTo
{
    public String traceNumber;
    public String companyName;
    public String storageCondition;
    public String transportMethod;
    public String distributeBatch;
    public String storageLocation;
    public Long distributePrice;
    public Long distributeQuantity;
    public String inspectionReport;
}
