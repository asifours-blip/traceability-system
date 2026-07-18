package com.qhx.back.model.to;

import lombok.Data;
@Data
public class RetailerTo
{
    public String traceNumber;
    private String companyName;
    private Long salePrice;
    private Long saleQuantity;
    private Long shelfLife;
    private String invoiceNo;
    private String saleTime;
    private Long timestamp;

}
