package com.company.cloud.portal.filter;

import com.company.cloud.auth.portal.PortalJwksClient;
import com.company.cloud.auth.portal.ServiceAuthProperties;
import com.company.cloud.auth.portal.ServiceCredentialVerifier;
import com.company.cloud.portal.config.PortalProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 内网接口鉴权（《27 - 服务间认证改造》§4.2 产品侧）。
 *
 * <p><b>取代 {@code X-Internal-Key}，但保留回退</b>：
 * <ul>
 *   <li>服务凭据已配置（{@code app.service-auth}）→ 走 RS256 验签（7 个校验点）</li>
 *   <li>未配置 → 回退旧的 {@code X-Internal-Key} 校验（灰度与回滚用）</li>
 * </ul>
 *
 * <p><b>为什么要自己缓存 body</b>：{@code body_sha256} 必须对**收到的原始字节**计算，
 * 而 Servlet 的输入流只能读一次。这里读完立即包一层可重放的 Wrapper，
 * 下游 Controller 照常能读到 body —— 不做这件事会导致下游拿到空 body（比不验签更糟）。
 *
 * <p>失败统一返回 {@code 401 + {code:1002}}，**不回显具体原因**（§4.1：区分原因等于给探测信号）。
 */
@Slf4j
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final String KEY_HEADER = "X-Internal-Key";
    private static final String ATTRIBUTE_PRODUCT = "internalProduct";

    private final PortalProperties portalProps;
    private final ServiceAuthProperties svcProps;
    private final ServiceCredentialVerifier verifier;
    private final PortalJwksClient jwksClient;
    private final StringRedisTemplate redis;

    public InternalServiceAuthFilter(PortalProperties portalProps,
                                     ServiceAuthProperties svcProps,
                                     ServiceCredentialVerifier verifier,
                                     PortalJwksClient jwksClient,
                                     StringRedisTemplate redis) {
        this.portalProps = portalProps;
        this.svcProps = svcProps;
        this.verifier = verifier;
        this.jwksClient = jwksClient;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 服务凭据未启用 → 回退旧密钥（行为与改造前一致）
        if (!svcProps.isConfigured()) {
            if (!checkInternalKey(request)) {
                reject(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // 读原始 body 并包成可重放请求（body hash 必须基于原始字节）
        byte[] rawBody = readAll(request);
        HttpServletRequest replayable = new ReplayableRequest(request, rawBody);

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            log.warn("[svc-auth] 缺少 Authorization Bearer uri={}", request.getRequestURI());
            reject(response);
            return;
        }
        String token = header.substring(7);

        ServiceCredentialVerifier.Result r = verifier.verify(
                token, rawBody, request.getMethod(), request.getRequestURI(),
                request.getQueryString(), svcProps.getProductCode(), svcProps.getPortalIssuer(),
                kid -> {
                    // kid 未命中时 PortalJwksClient.locate 内部会强刷一次（含防抖）
                    return jwksClient.locate(kid);
                });

        if (!r.ok()) {
            log.warn("[svc-auth] 验签失败 uri={} method={} reason={}",
                    request.getRequestURI(), request.getMethod(), r.reason());
            reject(response);
            return;
        }

        Claims claims = r.claims();
        // jti 去重（防重放）。Redis 不可用时 **fail-closed**（§4.1 第 ⑦ 条）
        String jti = claims.getId();
        String iss = claims.getIssuer();
        if (jti == null || jti.isBlank()) {
            log.warn("[svc-auth] 缺少 jti，拒绝（无法做重放防护）");
            reject(response);
            return;
        }
        if (redis == null) {
            log.error("[svc-auth] Redis 不可用，无法做 jti 去重 —— fail-closed 拒绝 uri={}",
                    request.getRequestURI());
            reject(response);
            return;
        }
        Boolean fresh;
        try {
            fresh = redis.opsForValue()
                    .setIfAbsent("rp:jti:" + iss + ":" + jti, "1", Duration.ofSeconds(120));
        } catch (Exception e) {
            log.error("[svc-auth] Redis 写入失败，fail-closed 拒绝 uri={} err={}",
                    request.getRequestURI(), e.toString());
            reject(response);
            return;
        }
        if (!Boolean.TRUE.equals(fresh)) {
            log.warn("[svc-auth] 重放请求 jti={} uri={}", jti, request.getRequestURI());
            reject(response);
            return;
        }

        // 供下游取"是哪个产品调过来的"
        request.setAttribute(ATTRIBUTE_PRODUCT, iss);
        chain.doFilter(replayable, response);
    }

    // ── 回退：旧密钥校验（与原 InternalKeyFilter 同语义）───────────

    private boolean checkInternalKey(HttpServletRequest request) {
        String configured = portalProps.getInternalKey();
        if (configured == null || configured.isBlank()) {
            log.error("[portal] 未配置 app.portal.internal-key，内网接口 {} 已拒绝（fail-closed）",
                    request.getRequestURI());
            return false;
        }
        String presented = request.getHeader(KEY_HEADER);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":1002,\"message\":\"未认证或 token 无效\",\"data\":null}");
    }

    private static byte[] readAll(HttpServletRequest request) throws IOException {
        try (var in = request.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** 把已读出的字节重新暴露为输入流，下游仍能正常读 body。 */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取场景不需要
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
