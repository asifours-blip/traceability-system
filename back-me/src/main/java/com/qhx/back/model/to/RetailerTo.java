package com.qhx.back.model.to;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RetailerTo
{
    private String traceNumber;
    private String companyName;
    // 用 BigDecimal 接收后再校验「正整数」，避免小数被静默截断
    private BigDecimal salePrice;
    private BigDecimal saleQuantity;
    // 保质期（天）
    private BigDecimal shelfLife;
    private String invoiceNo;
    // yyyy-MM-dd
    private String saleTime;
}
