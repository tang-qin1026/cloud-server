package com.company.cloud.portal.filter;

import com.company.cloud.portal.config.PortalProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内网接口鉴权（契约 §3.1 / §3.2）：校验请求头 {@code X-Internal-Key}。
 *
 * <p>作用范围仅 {@code /internal/*}（由 FilterRegistrationBean 限定 URL pattern），
 * 属普通 Servlet Filter，在 Spring Security 过滤链之前执行，**未通过直接短路**。
 *
 * <p><b>fail-closed</b>：密钥未配置（空）时一律拒绝，避免「配了 PRODUCT_xxx_BASE_URL
 * 却忘了配 KEY」导致内网接口裸奔。
 *
 * <p>另注：契约自检项要求「两个接口都校验 X-Internal-Key，且只监听内网」。
 * 监听面由部署负责（防火墙 + nginx不要反代 /internal/），本类只管密钥校验。
 */
@Slf4j
@RequiredArgsConstructor
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Key";

    private final PortalProperties props;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String configured = props.getInternalKey();
        String presented = request.getHeader(HEADER);

        if (configured == null || configured.isBlank()) {
            log.error("[portal] 未配置 app.portal.internal-key，内网接口 {} 已拒绝（fail-closed）", request.getRequestURI());
            reject(response);
            return;
        }
        if (presented == null || !constantTimeEquals(configured, presented)) {
            log.warn("[portal] 内网接口鉴权失败 uri={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40001,\"message\":\"无效的内部调用凭证\",\"data\":null}");
    }

    /** 定长比较，避免时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
