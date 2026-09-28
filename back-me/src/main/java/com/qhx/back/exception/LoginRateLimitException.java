package com.qhx.back.exception;

/**
 * 同一账号 + IP 短时间内登录失败次数超限：HTTP 429，携带需要等待的秒数（用于 Retry-After 响应头）。
 */
public class LoginRateLimitException extends RuntimeException
{
    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds, String message)
    {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds()
    {
        return retryAfterSeconds;
    }
}
