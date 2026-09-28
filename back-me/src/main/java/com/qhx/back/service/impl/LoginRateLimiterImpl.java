package com.qhx.back.service.impl;

import com.qhx.back.service.LoginRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版实现：key = 账号（忽略大小写、去空白）+ IP。
 * 滑动窗口内失败次数达到上限就锁定 lockoutSeconds；锁定到期后自动可重试（惰性清理，不需要定时任务）。
 */
@Service
public class LoginRateLimiterImpl implements LoginRateLimiter
{
    @Value("${auth.rate-limit.max-attempts:5}")
    private int maxAttempts;
    @Value("${auth.rate-limit.window-seconds:300}")
    private long windowSeconds;
    @Value("${auth.rate-limit.lockout-seconds:60}")
    private long lockoutSeconds;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket
    {
        int failures;
        long windowStartMillis;
        long lockedUntilMillis;
    }

    private static String key(String username, String clientIp)
    {
        String u = username == null ? "" : username.trim().toLowerCase();
        String ip = clientIp == null ? "" : clientIp.trim();
        return u + "::" + ip;
    }

    @Override
    public long secondsUntilRetry(String username, String clientIp)
    {
        Bucket b = buckets.get(key(username, clientIp));
        if (b == null) {
            return 0;
        }
        synchronized (b) {
            long remainMillis = b.lockedUntilMillis - System.currentTimeMillis();
            return remainMillis <= 0 ? 0 : (remainMillis + 999) / 1000;
        }
    }

    @Override
    public void recordFailure(String username, String clientIp)
    {
        Bucket b = buckets.computeIfAbsent(key(username, clientIp), k -> new Bucket());
        synchronized (b) {
            long now = System.currentTimeMillis();
            if (b.windowStartMillis == 0 || now - b.windowStartMillis > windowSeconds * 1000) {
                b.windowStartMillis = now;
                b.failures = 0;
            }
            b.failures++;
            if (b.failures >= maxAttempts) {
                b.lockedUntilMillis = now + lockoutSeconds * 1000;
                b.failures = 0;
                b.windowStartMillis = now;
            }
        }
    }

    @Override
    public void recordSuccess(String username, String clientIp)
    {
        buckets.remove(key(username, clientIp));
    }
}
