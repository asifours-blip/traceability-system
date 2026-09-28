package com.qhx.back.model.vo;

import com.qhx.back.model.ChainTx;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChainTxVerifyVO
{
    // ALREADY_FINAL / RECEIPT_CONFIRMED / RECEIPT_FAILED / STATE_CONFIRMED / CONFLICT / NOT_WRITTEN / NOT_SENT / INCONCLUSIVE
    private String conclusion;
    private String message;
    private ChainTx record;
}
