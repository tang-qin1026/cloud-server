package com.company.cloud.auth.security;

import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.tenant.mapper.EnterpriseStatusMapper;
import com.company.cloud.common.tenant.TenantContext;
import io.jsonwebtoken.Claims;
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
 * JWT 认证过滤器（对标任务书 AuthGuard）。
 *
 * <p>职责：
 * <ol>
 *   <li>从 {@code Authorization: Bearer <token>} 提取 token</li>
 *   <li>校验 JWT（过期/非法 → 40103）</li>
 *   <li>校验吊销列表（登出/改密/禁用 → 即时失效）</li>
 *   <li>注入 {@link CurrentUser} 到 SecurityContext</li>
 *   <li><b>V2025（M2）</b> 建立 {@link TenantContext}</li>
 * </ol>
 *
 * <p><b>为什么租户信息从库里读，而不是从 token 里取</b>：
 * 业务请求带的是<b>本系统 HS256 token</b>，它由 {@code AuthService} 在登录时签发，
 * 里面没有 {@code ut}/{@code ent}（那两个是<b>门户</b> token 的 claim）。
 * 而企业归属的权威虽然在门户，产品侧已把它投影到 {@code users.enterprise_id}。
 * 从本地投影读有两个好处：① 门户改了归属，下次请求自然跟随；② 不依赖门户是否已加 claim。
 *
 * <p>代价是每个已认证请求多一次主键查询；M2 先这样，**后续可用 Redis 缓存该投影**。
 *
 * <p>⚠️ {@link TenantContext} <b>必须在 finally 清除</b>，否则线程复用会串租户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final RevocationService revocationService;
    private final LoginThrottle loginThrottle; // 预留：可用于全局请求纬度兜底，当前以登录接口限流为主
    private final UserRepository userRepository;
    private final EnterpriseStatusMapper enterpriseStatusMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            authenticate(request);
            chain.doFilter(request, response);
        } finally {
            // 无论成功失败都要清，避免线程池复用导致串租户
            TenantContext.clear();
        }
    }

    private void authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return;
        }
        String token = header.substring(BEARER.length());
        try {
            Claims claims = jwtService.parse(token);
            // 只接受 access token；refresh token 不能用于业务请求
            if (!jwtService.isAccessToken(claims)) {
                return;
            }
            String jti = jwtService.getJti(claims);
            if (revocationService.isRevoked(jti)) {
                return;   // 已吊销 → 不认证，由 Security 入口统一返回 40103
            }
            Long userId = Long.valueOf(claims.getSubject());
            // 用户级 token 版本校验：账号被禁用/重置密码后版本 +1，
            // 该账号此前签发的所有 access token 立即失效（R-A03 禁用拦截）
            long tokenUv = claims.get("uv", Long.class) == null ? 0L : claims.get("uv", Long.class);
            if (revocationService.getUserTokenVersion(userId) != tokenUv) {
                return;   // uv 不一致 → 账号已被禁用/版本已升级 → 不认证
            }

            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            // 读本地投影，补齐租户信息（企业归属 / 企业管理员标记）
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return;   // 账号已不存在 → 不认证
            }

            // ── 企业状态复核（V2025 / M2）────────────────────────────
            // 门户只在登录时按四态拦截；token 签发后企业被停用/驳回的场景必须在这里挡。
            // fail-closed：查不到企业也按不可用处理，不认证 → Security 返回 40103。
            if (!user.isPersonal()) {
                String status = enterpriseStatusMapper.selectStatus(user.getEnterpriseId());
                if (!"active".equals(status)) {
                    log.warn("[tenant] 企业不可用，拒绝本次请求 userId={} enterpriseId={} status={}",
                            user.getId(), user.getEnterpriseId(), status);
                    return;
                }
            }

            CurrentUser cu = new CurrentUser(
                    user.getId(), username, role,
                    user.isPersonal() ? 0 : 1,
                    user.getEnterpriseId(),
                    user.getEnterpriseRole() == null ? 0 : user.getEnterpriseRole().intValue());

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            cu, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(auth);

            establishTenant(user);
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            // 非法/过期 token → 不认证，由 Security 入口统一返回 40103
        }
    }

    /**
     * 建立租户上下文。
     *
     * <ul>
     *   <li><b>平台管理员</b>（{@code role=admin} 且不是企业管理员）→ {@code bypass}：
     *       跨企业视图（现有管理端要看全部用户/审计），不能按单一企业过滤</li>
     *   <li>个人域用户 → {@code enterprise_id = 0}</li>
     *   <li>企业用户 → 其 {@code enterprise_id}</li>
     * </ul>
     */
    private void establishTenant(User user) {
        if (user.isAdmin() && !user.isEnterpriseAdmin()) {
            TenantContext.setBypass();
        } else if (user.isPersonal()) {
            TenantContext.setPersonal();
        } else {
            TenantContext.set(user.getEnterpriseId());
        }
    }
}
