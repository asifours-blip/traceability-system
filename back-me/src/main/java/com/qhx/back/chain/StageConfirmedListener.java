package com.qhx.back.chain;

import com.qhx.back.model.ChainTx;

/**
 * 阶段交易进入 CONFIRMED 之后的回调（发交易拿到成功回执，或查证确认）。
 * 只在 CONFIRMED 时调用；FAILED / UNKNOWN 不会触发。回调里的异常由 ChainTxService 吞掉并记日志，
 * 不影响交易本身的结果（已经上链的交易不能因为链下的后续处理失败而被报告成失败）。
 */
public interface StageConfirmedListener {
    void onStageConfirmed(ChainTx tx);
}
