package com.company.cloud.auth.portal;

import com.company.cloud.auth.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 门户 JWT 认证过滤器（契约 §5）。
 *
 * <p>与 {@code JwtAuthFilter}（本系统自签 HS256 令牌）共存，形成双令牌体系：
 * <ul>
 *   <li>本系统令牌 → JwtAuthFilter 认证成功 → 本过滤器直接跳过</li>
 *   <li>门户令牌（RS256）→ JwtAuthFilter 验签失败静默放行 → 本过滤器验签并注入身份</li>
 *   <li>两者都失败 → 不注入认证 → 由 Security 入口统一返回 401 / 40103</li>
 * </ul>
 *
 * <p>认证成功后注入的是**本地影子用户**的 {@link CurrentUser}，
 * 因此全部既有业务代码（@RequireRole、CurrentUser、审计）无需任何改动。
 *
 * <p>挂在 SecurityFilterChain 内（不是普通 Servlet Filter）：必须在
 * SecurityContextHolderFilter 之后运行，否则注入的认证会被清空。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PortalAuthProperties props;
    private final PortalJwtVerifier verifier;
    private final PortalShadowUserService shadowUserService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled() || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            PortalJwtVerifier.PortalToken token =
                    verifier.verify(header.substring(BEARER.length()));
            CurrentUser currentUser = shadowUserService.resolveOrCreate(token.portalUserId(), token.username());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    currentUser, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.getRole())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("[portal] 门户令牌认证通过 portalUserId={} localUserId={}",
                    token.portalUserId(), currentUser.getId());
        } catch (Exception e) {
            // 不是门户令牌 / 验签失败 / 过期 / 影子用户被禁用 → 不认证，交给 Security 入口返回 401
            log.debug("[portal] 门户令牌验签未通过: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }
}
