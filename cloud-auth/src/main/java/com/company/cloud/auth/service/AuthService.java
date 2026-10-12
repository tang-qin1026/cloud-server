package com.company.cloud.auth.service;

import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.BizException;
import com.company.cloud.auth.audit.AuditService;
import com.company.cloud.auth.dto.*;
import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.portal.PortalLoginClient;
import com.company.cloud.auth.portal.PortalLoginException;
import com.company.cloud.auth.portal.PortalLoginUser;
import com.company.cloud.auth.portal.PortalShadowUserService;
import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.auth.security.JwtService;
import com.company.cloud.auth.security.LoginThrottle;
import com.company.cloud.auth.security.RevocationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证核心服务（对标任务书 R-A01 ~ R-A04）
 *  - 登录（限流 + 锁定 + 签发双 token）
 *  - 刷新（refresh token 轮换）
 *  - 登出 / 修改密码（吊销旧 token）
 *  - 首登强制改密拦截
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final RevocationService revocationService;
    private final AuditService auditService;
    /** 登录回落门户统一认证（代理登录）；仅本地密码验不过时使用 */
    private final PortalLoginClient portalLoginClient;
    /** 复用已有的影子用户查/建逻辑（含唯一索引并发兜底），不另写一份 */
    private final PortalShadowUserService shadowUserService;

    /**
     * 登录：用户名 + 密码；限流；连续失败锁定；签发双 token
     */
    public LoginResponse login(LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);

        // 1. 限流：每 IP 每分钟 5 次
        if (throttle.isRateLimited(ip)) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }

        // 2. 锁定检查
        long remain = throttle.lockedRemainingMinutes(ip);
        if (remain > 0) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    ErrorCode.ACCOUNT_LOCKED.getMessage() +
                            "（" + remain + " 分钟后可再试）");
        }

        // 3. 用户校验：**本地优先**。
        //    本地验过就走原路径（零额外网络往返，门户挂了老用户也不受影响）；
        //    只有本地验不过才回落到门户统一认证（见 3b）。
        User user = userRepository.findByUsername(req.getUsername()).orElse(null);
        boolean localPassed = user != null
                && passwordEncoder.matches(req.getPassword(), user.getPasswordHash());
        if (!localPassed) {
            // 3b. 回落门户：门户验过后按 portal_user_id 查/建影子用户。
            //
            // ⚠️⚠️ 安全约束（改造说明 §7，勿改）——本地密码验不过之后，
            //   **绝不能再按用户名回查本地账号表**（一次都不能）。
            //   否则会出现提权：门户里有个普通用户，用户名恰好叫 admin；
            //   他输自己的密码 → 本地 bcrypt 对 admin 失败 → 若此处"按用户名再查一次本地表"，
            //   就会匹配到本地管理员 admin → 门户普通用户直接拿到云存储管理员权限。
            //
            //   唯一正确链路：调门户 → 拿门户 UUID → 按 portal_user_id 查/建影子用户。
            //   影子用户的 role 恒为普通用户（PortalShadowUserCreator 里硬编码 role="user"），
            //   且门户返回的 user.role **被我们忽略**（PortalLoginUser 根本不承载它）。
            user = loginViaPortal(req.getUsername(), req.getPassword(), ip);
        }

        // 4. 禁用检查
        if (user.isDisabled()) {
            auditService.loginFail(user.getUsername(), ip);
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 5. 解锁计数
        throttle.clearFailure(ip);

        // 6. 签发双 token（与管理员入口共用同一段签发逻辑，见 issueTokens）
        return issueTokens(user, ip);
    }

    /**
     * 管理员登录入口（{@code POST /api/admin/login}）。
     *
     * <p>与主登录入口的唯一区别：<b>只验本地密码，绝不回落门户、也不建任何映射</b>。
     * 原因见改造说明 §7：管理端登录与门户解耦后，"门户里恰好有个普通用户叫 admin
     * 从而拿到云存储管理员权限"在结构上就不可能发生。
     *
     * <p>限流与失败锁定与主入口共用，所以本入口不会成为绕过暴力破解的旁路。
     */
    public LoginResponse adminLogin(LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);

        if (throttle.isRateLimited(ip)) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        long remain = throttle.lockedRemainingMinutes(ip);
        if (remain > 0) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED,
                    ErrorCode.ACCOUNT_LOCKED.getMessage() +
                            "（" + remain + " 分钟后可再试）");
        }

        // 只查本地表、只验本地 bcrypt —— 这里【没有】任何门户调用，也不会建映射
        User user = userRepository.findByUsername(req.getUsername()).orElse(null);
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throttle.recordFailure(ip);
            auditService.loginFail(req.getUsername(), ip);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (user.isDisabled()) {
            auditService.loginFail(user.getUsername(), ip);
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        // 本入口只服务管理员；普通用户（含门户影子用户）不得从这里登录
        if (!user.isAdmin()) {
            throttle.recordFailure(ip);
            auditService.loginFail(user.getUsername(), ip);
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        throttle.clearFailure(ip);
        return issueTokens(user, ip);
    }

    /**
     * 签发本系统 HS256 双 token（普通登录与管理员登录<b>共用</b>，确保只有一套签发逻辑）。
     *
     * <p>回落门户成功后走的也是这里——所以无论用户来自本地还是门户，
     * 拿到的都是本系统令牌；{@code PortalJwtAuthFilter} 那套 RS256 验签链路保持不变。
     */
    private LoginResponse issueTokens(User user, String ip) {
        // 回填最近登录时间（V1002）
        user.setLastLoginAt(java.time.OffsetDateTime.now());
        userRepository.save(user);

        String access = jwtService.createAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refresh = jwtService.createRefreshToken(user.getId(), user.getUsername(), user.getRole());

        auditService.loginSuccess(user.getId(), user.getUsername(), ip);
        return new LoginResponse(access, refresh, userMap(user));
    }

    /**
     * 登录回落门户统一认证（代理登录）。
     *
     * <p>只在本系统本地校验失败时调用。门户验过后按 <b>portal_user_id</b>（不是用户名）
     * 查/建影子用户并返回其本地实体，后续签发逻辑与本地登录完全共用。
     *
     * <p><b>失败分流必须保持"密码错"与"服务不可用"的区分</b>（改造说明 §4.2 验收步骤 8）：
     * <ul>
     *   <li>2003 账号或密码错 → 记失败次数 + 审计，返回 {@code 40101}</li>
     *   <li>2004 账号被禁用   → 审计，返回 {@code 40102}</li>
     *   <li>不可达 / 超时 / 其它码 → <b>不记失败次数</b>（否则门户一挂就会把用户的 IP 锁死），
     *       返回 {@code 50000 系统繁忙，请稍后重试}，前端可引导重试</li>
     * </ul>
     */
    private User loginViaPortal(String username, String rawPassword, String ip) {
        PortalLoginUser portalUser;
        try {
            portalUser = portalLoginClient.login(username, rawPassword);
        } catch (PortalLoginException e) {
            switch (e.getReason()) {
                case BAD_CREDENTIALS -> {
                    throttle.recordFailure(ip);
                    auditService.loginFail(username, ip);
                    throw new BizException(ErrorCode.LOGIN_FAILED);
                }
                case DISABLED -> {
                    auditService.loginFail(username, ip);
                    throw new BizException(ErrorCode.ACCOUNT_DISABLED);
                }
                default -> {
                    // 门户故障：与"密码错"严格区分，且不累加失败次数
                    log.warn("[portal] 登录回落门户不可用 username={} reason={} err={}",
                            username, e.getReason(), e.getMessage());
                    throw new BizException(ErrorCode.SYSTEM_ERROR);
                }
            }
        }
        // 按门户 UUID 查/建影子用户。
        // 切忌按用户名查：门户有个 luobo，本地可能也有个同名的另一个人，会串号。
        CurrentUser shadow = shadowUserService.resolveOrCreate(portalUser.portalUserId(), portalUser.username());
        return userRepository.findById(shadow.getId())
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 刷新 access：校验 refresh token（未吊销）→ 轮换签发新的双 token
     */
    @Transactional
    public LoginResponse refresh(RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtService.parse(req.getRefreshToken());
        } catch (Exception e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if (!jwtService.isRefreshToken(claims)) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        // 吊销的 refresh 不可用
        if (revocationService.isRevoked(jwtService.getJti(claims))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId).orElseThrow(() -> new BizException(ErrorCode.TOKEN_INVALID));
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        // 用户级 token 版本校验：账号被禁用/重置密码后版本 +1，旧 refresh token 不可再用（R-A03）
        long tokenUv = claims.get("uv", Long.class) == null ? 0L : claims.get("uv", Long.class);
        if (revocationService.getUserTokenVersion(userId) != tokenUv) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        // 轮换：吊销旧 refresh，签发新双 token
        revocationService.revokeWithRemaining(jwtService.getJti(claims), refreshRemainingMillis(claims));
        String access = jwtService.createAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refresh = jwtService.createRefreshToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(access, refresh, userMap(user));
    }

    /**
     * 登出：吊销当前 access token（刷新 token 由客户端一并丢弃；如需严格吊销也在此处理）
     */
    @Transactional
    public void logout(CurrentUser cu, HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                revocationService.revokeAccess(jwtService.getJti(claims));
            } catch (Exception ignored) {
                // token 已过期则无需吊销
            }
        }
    }

    /**
     * 修改密码：校验旧密码 → 更新（置 must_change_password=false）→ 吊销该用户全部旧 token
     * 注：吊销全部旧 token 依赖于用户维度记录，此处以吊销当前 access 为例；
     *     严格实现可引入 per-user token 索引（如 user:{id}:tokens 集合）以支持全量吊销。
     */
    @Transactional
    public void changePassword(CurrentUser cu, ChangePasswordRequest req) {
        User user = userRepository.findById(cu.getId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
        auditService.changePassword(user.getId(), user.getUsername());
        // 用户自助改密 → 用户 token 版本 +1，该账号此前签发的全部 token 立即失效（R-A03）
        long ver = revocationService.bumpUserTokenVersion(user.getId());
        log.info("改密成功 userId={}，已吊销全部历史 token (uv={})", user.getId(), ver);
    }

    /**
     * 当前用户资料（对标任务书 R-A09）
     */
    public Map<String, Object> profile(CurrentUser cu) {
        User user = userRepository.findById(cu.getId())
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        return userMap(user);
    }

    private Map<String, Object> userMap(User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("role", user.getRole());
        m.put("quotaBytes", user.getQuotaBytes());
        m.put("usedBytes", user.getUsedBytes());
        m.put("mustChangePassword", user.getMustChangePassword());
        m.put("nickname", user.getNickname());
        m.put("email", user.getEmail());
        m.put("lastLoginAt", user.getLastLoginAt());
        m.put("extraBytes", user.getExtraBytes() == null ? 0L : user.getExtraBytes());
        m.put("extraExpireAt", user.getExtraExpireAt());
                return m;
    }

    private String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private long refreshRemainingMillis(Claims claims) {
        Date exp = claims.getExpiration();
        return exp == null ? 0 : Math.max(0, exp.getTime() - System.currentTimeMillis());
    }
}