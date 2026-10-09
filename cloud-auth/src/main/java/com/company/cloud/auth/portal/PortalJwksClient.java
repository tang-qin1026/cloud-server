package com.company.cloud.auth.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门户 JWKS 公钥缓存（契约 §1.5）。
 *
 * <p>必须实现的三件事：
 * <ol>
 *   <li><b>启动时拉取</b>并缓存 —— {@link #warmUp()}（@PostConstruct）</li>
 *   <li><b>每 12 小时刷新</b> —— {@link #scheduledRefresh()}</li>
 *   <li><b>kid 在缓存里找不到时立即强制刷新</b> —— {@link #locate(String)}</li>
 * </ol>
 * 少了第 3 条，门户一换密钥验签会全挂，且现象是「突然所有人 401」，极难排查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalJwksClient {

    private final PortalAuthProperties props;
    private final ObjectMapper objectMapper;

    /** kid → RSA 公钥。刷新时整体替换（门户轮换期会同时发布新旧公钥）。 */
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();

    /** 最近一次成功拉取的时刻（毫秒）。0 表示从未成功。 */
    private volatile long lastFetchAt = 0L;

    private final Object refreshLock = new Object();

    private RestClient restClient;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        if (!props.isEnabled()) {
            log.info("[portal] 门户 JWT 验签已关闭（app.portal.auth.enabled=false）");
            return;
        }
        warmUp();
    }

    /** 启动时拉取（失败不阻断启动，仅告警；后续 kid 未命中或定时任务会再试）。 */
    public void warmUp() {
        refresh("startup");
    }

    @Scheduled(cron = "${app.portal.auth.refresh-cron:0 0 */12 * * *}")
    public void scheduledRefresh() {
        if (!props.isEnabled()) {
            return;
        }
        refresh("scheduled");
    }

    /**
     * 按 kid 取公钥。
     *
     * @param kid JWT 头部 kid；可能为 null（门户未下发 kid）
     * @return 公钥；找不到返回 null（调用方按验签失败处理）
     */
    public PublicKey locate(String kid) {
        if (!props.isEnabled()) {
            return null;
        }
        if (kid != null) {
            PublicKey cached = keyCache.get(kid);
            if (cached != null && !isStale()) {
                return cached;                                  // 命中且未过期
            }
            // 未命中（密钥轮换 / 首次）或缓存过期 → 立即拉取
            refresh(cached == null ? "kid-miss" : "stale");
            return keyCache.get(kid);
        }
        // 无 kid：仅在缓存为空或过期时拉取，避免每次请求都打门户
        if (keyCache.isEmpty() || isStale()) {
            refresh("no-kid");
        }
        return keyCache.isEmpty() ? null : keyCache.values().iterator().next();
    }

    /** 当前缓存的 kid 集合（健康检查 / 排障用）。 */
    public Map<String, String> cacheInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("cachedKids", String.join(",", keyCache.keySet()));
        info.put("lastFetchAt", lastFetchAt == 0 ? "never" : String.valueOf(lastFetchAt));
        info.put("stale", String.valueOf(isStale()));
        return info;
    }

    private boolean isStale() {
        long ttl = Math.max(1L, props.getRefreshHours()) * 3600_000L;
        return System.currentTimeMillis() - lastFetchAt > ttl;
    }

    /**
     * 拉取并替换公钥缓存。失败时保留旧缓存（宁可短时用旧 key 也不要全员 401）。
     */
    public void refresh(String reason) {
        synchronized (refreshLock) {
            try {
                String body = restClient.get()
                        .uri(props.getJwksUrl())
                        .retrieve()
                        .body(String.class);
                JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
                JsonNode keys = root.path("keys");
                Map<String, PublicKey> fresh = new LinkedHashMap<>();
                if (keys.isArray()) {
                    for (JsonNode k : keys) {
                        if (!"RSA".equalsIgnoreCase(k.path("kty").asText())) {
                            continue;
                        }
                        String kid = k.path("kid").asText(null);
                        String n = k.path("n").asText(null);
                        String e = k.path("e").asText(null);
                        if (kid == null || n == null || e == null) {
                            continue;
                        }
                        fresh.put(kid, toRsaPublicKey(n, e));
                    }
                }
                if (fresh.isEmpty()) {
                    log.warn("[portal] JWKS 拉取成功但未解析出任何 RSA 公钥（reason={}, url={}），保留旧缓存",
                            reason, props.getJwksUrl());
                    return;
                }
                keyCache.clear();
                keyCache.putAll(fresh);
                lastFetchAt = System.currentTimeMillis();
                log.info("[portal] JWKS 刷新成功 reason={} kids={}", reason, fresh.keySet());
            } catch (Exception ex) {
                log.error("[portal] JWKS 刷新失败 reason={} url={} err={}（保留旧缓存）",
                        reason, props.getJwksUrl(), ex.toString());
            }
        }
    }

    private static PublicKey toRsaPublicKey(String modulusB64Url, String exponentB64Url) throws Exception {
        // JWKS 的 n/e 是 base64url（通常无 padding），getUrlDecoder 两种都能吃
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(modulusB64Url));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(exponentB64Url));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }
}
