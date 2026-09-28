package com.qhx.back.file;

/**
 * 上传被拒绝或未能完成：status 为 HTTP 状态码，errorCode 为稳定的机器可读错误码（前端按它给提示）。
 * cid 仅在「已写入 IPFS 但核对失败」时有值，调用方据此决定是否取消 pin。
 */
public class FileRejectedException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final String cid;

    public FileRejectedException(int status, String errorCode, String message) {
        this(status, errorCode, message, null, null);
    }

    public FileRejectedException(int status, String errorCode, String message, String cid, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.cid = cid;
    }

    public int getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getCid() {
        return cid;
    }
}
