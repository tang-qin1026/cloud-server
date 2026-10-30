package com.company.cloud.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录限流 + 连续失败锁定（对标任务书 R-A01）。
 *
 * <h3>三类状态必须严格区分（D-02 修复：2026-10-30）</h3>
 * <ol>
 *   <li><b>频率超限</b> —— 每 IP 每分钟尝试次数过多 → {@code 42902}「登录过于频繁」。
 *       这**不是**密码错误；此前错用 40101，导致"密码正确却提示用户名或密码错误"，
 *       联调时极易误判为账号被改密。</li>
 *   <li><b>密码错误</b> —— {@code 40101}，并**带出剩余尝试次数**。</li>
 *   <li><b>已锁定</b> —— {@code 42301}，带剩余分钟数。</li>
 * </ol>
 *
 * <p><b>成功登录不占频率额度</b>：每次成功会回退一次计数。
 * 否则正常演示时"连续登录 3 个账号"就会把自己限流掉 —— 这是真实踩过的。
 *
 * <h3>为什么「锁定」按账号而不是按 IP</h3>
 * 原先失败计数与锁定都按 IP 记账，但提示文案写的是"**账号**已临时锁定"。
 * 后果：同一个出口 IP（办公室 NAT / 联调机）下，一个人输错 5 次，
 * **同一 IP 的所有账号一起被锁 15 分钟**（真实踩过：自测输错密码后
 * 连 admin 都登不进，整套回归全红）。
 *
 * 现在：
 * <ul>
 *   <li><b>频率限制</b> → 按 IP（防暴力爆破的总闸，成功回退）</li>
 *   <li><b>失败计数 / 锁定</b> → 按**账号**（与提示文案一致，不连坐他人）</li>
 * </ul>
 *
 * <p>权衡：按账号锁存在"别人故意输错把你锁掉"的骚扰可能；
 * 内网产品可接受，且比"全员被锁"的伤害小得多。
 *
 * <p>三个阈值读 {@code app.security.*} 配置（此前写死在代码里，
 * 配置文件里的 max-login-failures / lock-minutes / rate-limit-per-minute 其实是死的）。
 */
@Slf4j
@Service
public class LoginThrottle {

    private final StringRedisTemplate redis;

    /** 连续失败多少次锁定。 */
    private final int maxFailures;

    /** 锁定时长（分钟）。 */
    private final int lockMinutes;

    /** 每 IP 每分钟允许的登录尝试次数。 */
    private final int rateLimitPerMinute;

    public LoginThrottle(StringRedisTemplate redis,
                         @Value("${app.security.max-login-failures:5}") int maxFailures,
                         @Value("${app.security.lock-minutes:15}") int lockMinutes,
                         @Value("${app.security.rate-limit-per-minute:30}") int rateLimitPerMinute) {
        this.redis = redis;
        this.maxFailures = Math.max(1, maxFailures);
        this.lockMinutes = Math.max(1, lockMinutes);
        this.rateLimitPerMinute = Math.max(1, rateLimitPerMinute);
    }

    private static final String RATE_KEY = "auth:rate:";
    private static final String FAIL_KEY = "auth:fail:";
    private static final String LOCK_KEY = "auth:lock:";

    /**
     * 记一次尝试；返回**还允许的剩余秒数**（0 = 未超限，可继续）。
     *
     * <p>用"加一次再判断"而不是先读后写：Redis INCR 原子，并发下不会漏计。
     */
    public long rateLimitRetryAfterSeconds(String ip) {
        String k = RATE_KEY + ip;
        Long n = redis.opsForValue().increment(k);
        if (n != null && n == 1L) {
            redis.expire(k, Duration.ofMinutes(1));
        }
        if (n == null || n <= rateLimitPerMinute) {
            return 0L;
        }
        Long ttl = redis.getExpire(k);
        return (ttl == null || ttl < 0) ? 60L : ttl;
    }

    /** 兼容旧调用：是否触发频率限制。 */
    public boolean isRateLimited(String ip) {
        return rateLimitRetryAfterSeconds(ip) > 0;
    }

    /**
     * 登录成功：回退一次 IP 频率计数（成功不占额度），并清空**该账号**的失败计数。
     */
    public void recordSuccess(String ip, String username) {
        String rateKey = RATE_KEY + ip;
        Long n = redis.opsForValue().decrement(rateKey);
        // 减到 0 以下说明 key 已过期/不存在，清掉避免残留负数
        if (n != null && n <= 0) {
            redis.delete(rateKey);
        }
        clearFailure(username);
    }

    /**
     * 记录一次登录失败；返回本次失败后的**剩余尝试次数**（0 表示即将/已经锁定）。
     */
    public long recordFailure(String username) {
        String k = FAIL_KEY + accountKey(username);
        Long n = redis.opsForValue().increment(k);
        if (n != null && n >= maxFailures) {
            lock(username);
            redis.delete(k);
            return 0L;
        }
        long used = n == null ? 0 : n;
        return Math.max(0, maxFailures - used);
    }

    /** 某账号已连续失败次数（用于提示）。 */
    public long failureCount(String username) {
        String v = redis.opsForValue().get(FAIL_KEY + accountKey(username));
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 某账号距离解锁还剩多少秒（0 = 未锁定）。 */
    public long lockedRemainingSeconds(String username) {
        Long ttl = redis.getExpire(LOCK_KEY + accountKey(username));
        return (ttl == null || ttl < 0) ? 0 : ttl;
    }

    /** 是否已锁定；若锁定返回剩余分钟数（向上取整；0 表示未锁定）。 */
    public long lockedRemainingMinutes(String username) {
        long sec = lockedRemainingSeconds(username);
        return sec <= 0 ? 0 : Math.max(1, (sec + 59) / 60);
    }

    public int getLockMinutes() {
        return lockMinutes;
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    private void lock(String username) {
        redis.opsForValue().set(LOCK_KEY + accountKey(username), "1",
                Duration.ofMinutes(lockMinutes));
        log.warn("[login] 账号已锁定 {} 分钟: {}", lockMinutes, username);
    }

    /** 登录成功后清空该账号的失败计数。 */
    public void clearFailure(String username) {
        redis.delete(FAIL_KEY + accountKey(username));
    }

    /** 账号维度 key：统一小写，避免大小写不同绕过计数。 */
    private static String accountKey(String username) {
        return username == null ? "?" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}