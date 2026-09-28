package com.qhx.back.chain;

/**
 * chain_tx 记录的状态。
 * <pre>
 * PENDING ──标记后发请求──> SUBMITTED ──> CONFIRMED  回执 status=0x0
 *                                    ├─> FAILED     回执失败（revert）/ WeBASE 拒绝 / 未送达
 *                                    └─> UNKNOWN    读超时、响应中断、坏 JSON、5xx、WeBASE 回执超时
 * UNKNOWN / SUBMITTED ──查证──> CONFIRMED / FAILED（冲突），或保持 UNKNOWN 但释放业务键（链上确认未写入）
 * </pre>
 */
public enum ChainTxState {
    /** 已记录提交意图，尚未发出请求 */
    PENDING,
    /** 已标记为即将发出（或已发出），等待 WeBASE-Front 的结果 */
    SUBMITTED,
    /** 回执 status 成功，或查证确认本次签名地址以相同数据写入了该阶段 */
    CONFIRMED,
    /** 回执失败 / revert、WeBASE 在发送前拒绝、请求未送达，或查证发现阶段已被他人/其他数据写入 */
    FAILED,
    /** 请求可能已发出但拿不到可判定的结果；不自动重发，需要查证 */
    UNKNOWN;

    /** 占用业务键：同一业务键同时只允许一条这些状态的记录 */
    public boolean inflight() {
        return this == PENDING || this == SUBMITTED || this == UNKNOWN;
    }
}
