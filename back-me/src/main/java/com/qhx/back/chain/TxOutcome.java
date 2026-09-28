package com.qhx.back.chain;

import lombok.Getter;

/**
 * 一次发交易（或按哈希查回执）的分类结果。只描述 WeBASE-Front 告诉了我们什么，不做业务决策。
 * 分类依据见 docs/webase-front-contract.md（WeBASE-Front v1.5.5 + FISCO BCOS 2.7.2 实测）。
 */
@Getter
public final class TxOutcome {

    public enum Kind {
        /** 回执 status=0x0 且带交易哈希与块高：链上成功 */
        CONFIRMED,
        /** 回执带交易哈希与块高，但 status 非 0x0：交易已打包、执行失败（revert 等） */
        REVERTED,
        /** WeBASE-Front 在签名/发送之前就拒绝了请求（如签名用户不存在、参数与 ABI 不符），交易没有发出 */
        REJECTED,
        /** 连不上 WeBASE-Front（连接被拒、连接超时），请求没有送达，交易没有发出 */
        NOT_SENT,
        /** 请求可能已送达、交易可能已发出，但拿不到可判定的结果（读超时、响应中断、坏 JSON、5xx、WeBASE 回执超时） */
        UNKNOWN
    }

    private final Kind kind;
    private final String txHash;
    private final Long blockNumber;
    /** 回执 status 原值，例如 0x0 / 0x16 / 50001 */
    private final String receiptStatus;
    /** 失败或未知的原因：revert 文本、WeBASE errorMessage、传输异常说明 */
    private final String reason;
    /** WeBASE-Front 422 响应体里的 code，其他情况为 null */
    private final Integer frontCode;

    private TxOutcome(Kind kind, String txHash, Long blockNumber, String receiptStatus, String reason, Integer frontCode) {
        this.kind = kind;
        this.txHash = txHash;
        this.blockNumber = blockNumber;
        this.receiptStatus = receiptStatus;
        this.reason = reason;
        this.frontCode = frontCode;
    }

    public static TxOutcome confirmed(String txHash, Long blockNumber, String receiptStatus) {
        return new TxOutcome(Kind.CONFIRMED, txHash, blockNumber, receiptStatus, null, null);
    }

    public static TxOutcome reverted(String txHash, Long blockNumber, String receiptStatus, String reason) {
        return new TxOutcome(Kind.REVERTED, txHash, blockNumber, receiptStatus, reason, null);
    }

    public static TxOutcome rejected(Integer frontCode, String reason) {
        return new TxOutcome(Kind.REJECTED, null, null, null, reason, frontCode);
    }

    public static TxOutcome notSent(String reason) {
        return new TxOutcome(Kind.NOT_SENT, null, null, null, reason, null);
    }

    public static TxOutcome unknown(String txHash, String receiptStatus, String reason) {
        return new TxOutcome(Kind.UNKNOWN, txHash, null, receiptStatus, reason, null);
    }

    @Override
    public String toString() {
        return kind + "(hash=" + txHash + ", block=" + blockNumber + ", status=" + receiptStatus
                + ", code=" + frontCode + ", reason=" + reason + ")";
    }
}
