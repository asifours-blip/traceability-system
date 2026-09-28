package com.qhx.back.exception;

/**
 * 业务规则不满足，携带 HTTP 状态码（400 / 403 / 404 / 409 等）与可选的数据。
 */
public class BusinessException extends RuntimeException
{
    private final int status;
    private final transient Object data;

    public BusinessException(int status, String message)
    {
        this(status, message, null);
    }

    public BusinessException(int status, String message, Object data)
    {
        super(message);
        this.status = status;
        this.data = data;
    }

    public int getStatus()
    {
        return status;
    }

    public Object getData()
    {
        return data;
    }
}
