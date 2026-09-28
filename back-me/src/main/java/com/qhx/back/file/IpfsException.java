package com.qhx.back.file;

/**
 * kubo 调用失败。kind 区分「内容在本节点上找不到」与「节点不可用 / 其他错误」，两者对调用方的含义不同。
 */
public class IpfsException extends RuntimeException {

    public enum Kind {
        /** 节点正常，但本地没有这个 CID 的块（离线模式下即确定缺失）：已被取消 pin 并回收，或从未存入 */
        MISSING,
        /** 连不上、超时、响应无法解析等：内容是否存在未知 */
        UNAVAILABLE,
        /** 节点返回了其他错误（如 CID 格式不对） */
        ERROR
    }

    private final Kind kind;

    public IpfsException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public IpfsException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
