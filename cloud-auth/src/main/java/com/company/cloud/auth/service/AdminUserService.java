package com.company.cloud.auth.service;

import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.BizException;
import com.company.cloud.auth.audit.AuditService;
import com.company.cloud.auth.dto.CreateUserRequest;
import com.company.cloud.auth.dto.UpdateUserRequest;
import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.security.RevocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 管理端用户管理服务（对标任务书 R-A06 ~ R-A08）
 *  - 创建用户（初始密码可随机，初始配额默认 20GB）
 *  - 禁用/启用、配额调整、角色变更
 *  - 重置密码（重置后强制首登改密）
 *  - 用户列表（分页 + 搜索 + 状态筛选）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RevocationService revocationService;
    private final com.company.cloud.auth.tenant.TenantGuard tenantGuard;

    @Value("${app.role.default-quota-bytes}")
    private long defaultQuotaBytes;

    private final SecureRandom random = new SecureRandom();

    /**
     * 用户列表：分页、按用户名搜索、按状态筛选
     */
    public Page<User> list(String keyword, String status, Pageable pageable) {
        return userRepository.search(
                (keyword == null || keyword.isBlank()) ? null : keyword.trim(),
                (status == null || status.isBlank()) ? null : status.trim(),
                pageable);
    }

    /**
     * 创建用户；返回初始密码（随机生成时由管理员告知用户）
     */
    @Transactional
    public String create(CreateUserRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        String rawPwd = req.getInitialPassword() == null || req.getInitialPassword().isBlank()
                ? randomPassword()
                : req.getInitialPassword();
        String role = (req.getRole() == null || req.getRole().isBlank()) ? "user" : req.getRole();
        long quota = (req.getQuotaBytes() == null || req.getQuotaBytes() <= 0)
                ? defaultQuotaBytes : req.getQuotaBytes();

        User user = User.builder()
                .username(req.getUsername())
                .passwordHash(passwordEncoder.encode(rawPwd))
                .role(role)
                .quotaBytes(quota)
                .usedBytes(0L)
                .status("active")
                .mustChangePassword(true)   // 新用户首登强制改密
                .nickname(req.getNickname())
                .email(validateEmailUnique(req.getEmail(), null))
                .build();
        userRepository.save(user);
        log.info("[admin] 创建用户 username={} role={} quota={}", user.getUsername(), role, quota);
        return rawPwd; // 随机密码会在此返回，供管理员安全转交
    }

    /**
     * 更新用户：禁用/启用、调整配额、变更角色。
     *
     * <p>A3 自我保护：禁止 admin 禁用/降级自己，且禁用/降级任一 admin 时须保证系统
     * 至少保留一个 active 状态的 admin，避免产生管理死锁（无人可再管理账号）。
     *
     * @param operatorId 当前操作者 id（来自认证上下文）
     */
    @Transactional
    public void update(Long id, UpdateUserRequest req, Long operatorId) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        // 第三道防线：跨企业按 id 操作一律按「不存在」处理（是否真拒绝由 tenant.enforce 控制）
        tenantGuard.assertTenant(user.getEnterpriseId(), ErrorCode.USER_NOT_FOUND, "admin.user");

        // A3-1：禁止操作者禁用/降级自己
        boolean selfOp = operatorId != null && operatorId.equals(user.getId());
        boolean disableTarget = req.getStatus() != null && "disabled".equals(req.getStatus());
        boolean demoteTarget = req.getRole() != null && !"admin".equals(req.getRole());
        if (selfOp && (disableTarget || demoteTarget)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能禁用自己的账号或降低自己的管理员权限");
        }

        // A3-2：若目标是管理员，且本次操作会使其失去 admin 能力（禁用或降级为非 admin），
        // 则必须保证系统中仍存在其他 active 管理员，否则拒绝，避免管理死锁。
        boolean isAdminTarget = "admin".equals(user.getRole());
        boolean losesAdmin = (isAdminTarget && disableTarget)
                || (isAdminTarget && demoteTarget);
        if (losesAdmin) {
            long activeAdminCount = userRepository.countByRoleAndStatus("admin", "active");
            // 目标当前仍为 active 管理员时，从其计数中扣除，计算「其余活跃管理员数」
            long remainingActiveAdmin = activeAdminCount - (user.isDisabled() ? 0 : 1);
            if (remainingActiveAdmin <= 0) {
                throw new BizException(ErrorCode.FORBIDDEN, "系统至少需要保留一个启用的管理员，不能禁用/降级最后一个管理员");
            }
        }

        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            if (disableTarget && !user.isDisabled()) {
                auditService.disableUser(operatorId == null ? 0L : operatorId, id);
                // 禁用账号 → 用户 token 版本 +1，该账号所有已签发 token 立即过期（R-A03 禁用拦截）
                long ver = revocationService.bumpUserTokenVersion(id);
                log.info("[admin] 禁用账号 userId={} 已吊销全部 token (uv={})", id, ver);
            }
            user.setStatus(req.getStatus());
        }
        if (req.getRole() != null && !req.getRole().isBlank() && !req.getRole().equals(user.getRole())) {
            user.setRole(req.getRole());
            // 角色变更(含升降级)时吊销该用户全部已签发 token，避免旧 JWT 携带旧角色继续生效(与 demote 一致)
            long ver = revocationService.bumpUserTokenVersion(id);
            log.info("[admin] 变更角色 userId={} role -> {}，已吊销全部 token (uv={})", id, req.getRole(), ver);
        }
        if (req.getQuotaBytes() != null) {
            // 校验口径：修改后的免费额度 + 充值额度(extra) 必须 > 已用量，否则拒绝（对标 R-A08）
            long extra = user.getExtraBytes() == null ? 0L : user.getExtraBytes();
            long newTotal = req.getQuotaBytes() + extra;
            if (newTotal <= user.getUsedBytes()) {
                throw new BizException(ErrorCode.QUOTA_TOO_LOW,
                        ErrorCode.QUOTA_TOO_LOW.getMessage() +
                                "（免费额度+充值额度需大于当前已用量 " + user.getUsedBytes() + " 字节）");
            }
            long oldQuota = user.getQuotaBytes();
            user.setQuotaBytes(req.getQuotaBytes());
            auditService.quotaChange(operatorId == null ? 0L : operatorId, id, oldQuota, req.getQuotaBytes());
        }
        if (req.getNickname() != null) {
            user.setNickname(req.getNickname());
        }
        if (req.getEmail() != null) {
            user.setEmail(validateEmailUnique(req.getEmail(), id));
        }
        userRepository.save(user);
    }

    /**
     * 校验邮箱是否已被其他用户占用；通过则原样返回，空串归一为 null。
     */
    private String validateEmailUnique(String email, Long excludeId) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String norm = email.trim();
        userRepository.findByEmail(norm).ifPresent(existing -> {
            if (excludeId == null || !existing.getId().equals(excludeId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "邮箱已被使用：" + norm);
            }
        });
        return norm;
    }

    /**
     * 重置密码：更新为指定/随机密码，并置 must_change_password=true（对标 R-A07）
     */
    @Transactional
    public String resetPassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        // 第三道防线：跨企业按 id 操作一律按「不存在」处理（是否真拒绝由 tenant.enforce 控制）
        tenantGuard.assertTenant(user.getEnterpriseId(), ErrorCode.USER_NOT_FOUND, "admin.user");
        String raw = (newPassword == null || newPassword.isBlank()) ? randomPassword() : newPassword;
        user.setPasswordHash(passwordEncoder.encode(raw));
        user.setMustChangePassword(true);
        userRepository.save(user);
        // 重置密码 → 用户 token 版本 +1，该账号所有已签发 token（access/refresh）立即过期
        long ver = revocationService.bumpUserTokenVersion(id);
        log.info("[admin] 重置密码 userId={} 已吊销全部 token (uv={})", id, ver);
        return raw;
    }

    private String randomPassword() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, 16);
    }
    /**
     * 用户身份降级（A 组新增接口）：管理员将指定用户角色降为 {@code user}。
     *
     * <p>降级规则（A3 自我保护）：
     * <ul>
     *   <li>禁止降级自己的账号；</li>
     *   <li>目标为管理员时，须保证系统中仍至少保留一个 active 管理员（防管理死锁）；</li>
     *   <li>目标当前已是普通用户（非 admin）时，返回错误提示。</li>
     * </ul>
     * 降级成功后，该用户全部已签发 token 立即失效（uv +1）。
     *
     * @param id         目标用户 id
     * @param operatorId 当前操作者 id（来自认证上下文）
     */
    @Transactional
    public void demote(Long id, Long operatorId) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        // 第三道防线：跨企业按 id 操作一律按「不存在」处理（是否真拒绝由 tenant.enforce 控制）
        tenantGuard.assertTenant(user.getEnterpriseId(), ErrorCode.USER_NOT_FOUND, "admin.user");

        // 目标非管理员：无降级必要
        if (!"admin".equals(user.getRole())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该用户当前不是管理员，无需降级");
        }

        // A3-1：禁止降级自己
        if (operatorId != null && operatorId.equals(user.getId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能降低自己的管理员权限");
        }

        // A3-2：最后管理员保护——降级后须至少保留一个 active 管理员
        long activeAdminCount = userRepository.countByRoleAndStatus("admin", "active");
        long remainingActiveAdmin = activeAdminCount - (user.isDisabled() ? 0 : 1);
        if (remainingActiveAdmin <= 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "系统至少需要保留一个启用的管理员，不能降级最后一个管理员");
        }

        String oldRole = user.getRole();
        user.setRole("user");
        userRepository.save(user);
        auditService.demoteUser(operatorId == null ? 0L : operatorId, id, oldRole, "user");
        // 降级后该用户历史 token 全部失效
        long ver = revocationService.bumpUserTokenVersion(id);
        log.info("[admin] 降级用户 userId={} role {} -> user，已吊销全部 token (uv={})", id, oldRole, ver);
    }
}