package com.qhx.back.exception;

/**
 * 认证/鉴权失败，携带 HTTP 状态码（401 / 403）。
 */
public class AuthException extends RuntimeException
{
    private final int status;

    public AuthException(int status, String message)
    {
        super(message);
        this.status = status;
    }

    public int getStatus()
    {
        return status;
    }
}
