package com.qhx.back.service;

/**
 * 登录失败限流：按「账号 + IP」维度统计。超过阈值后锁定一段时间，登录成功清零。
 * 纯内存实现，进程重启即清空——只用于抵御短时间的密码猜测，不是持久化的安全审计。
 */
public interface LoginRateLimiter
{
    // 还需要等待的秒数；未被限流返回 0
    long secondsUntilRetry(String username, String clientIp);

    // 记一次失败
    void recordFailure(String username, String clientIp);

    // 登录成功，清零该账号 + IP 的计数
    void recordSuccess(String username, String clientIp);
}
