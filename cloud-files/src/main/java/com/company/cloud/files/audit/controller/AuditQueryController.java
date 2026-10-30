package com.company.cloud.files.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.PageResult;
import com.company.cloud.common.result.Result;
import com.company.cloud.files.audit.dto.AuditLogVO;
import com.company.cloud.files.audit.entity.AuditLog;
import com.company.cloud.files.audit.mapper.AuditLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询（R-C09）。
 *
 * <p><b>鉴权：</b>A 组 JWT，从 SecurityContext 取当前用户与角色（与 B 组一致），无需 X-User-Id / X-User-Role 头。
 * 权限规则不变：admin 可全查（含指定任意 userId），普通用户强制只查自己。
 */
@Tag(name = "审计日志", description = "审计日志查询（C 组）")
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditQueryController {

    private final AuditLogMapper auditLogMapper;
    /** M3 数据范围：审计日志按范围限定可见的操作者。 */
    private final com.company.cloud.auth.org.OrgService orgService;
    /** 权限点校验（审计查看需要 admin.audit.read）。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;

    @Operation(summary = "分页查询审计日志（R-C09）：admin 全查，普通用户仅本人")
    @GetMapping
    public Result<PageResult<AuditLogVO>> query(
            Authentication authentication,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        CurrentUser cu = currentUser(authentication);
        // 2026-10-29 收口：审计日志此前**没有任何权限校验**，
        // 员工账号（13 项用户端权限）也能直接查到数据范围内容。
        // 审计属管理员职责，统一要求 admin.audit.read
        // （企业管理员的 25 项里有；员工没有 → 40301）。
        permissionChecker.require(cu, "admin.audit.read");

        QueryWrapper<AuditLog> qw = new QueryWrapper<>();
        if (cu.isAdmin()) {
            // 管理员：可按任意 userId 过滤，不传则全量
            qw.eq(userId != null, "user_id", userId);
        } else {
            // 普通用户：按**数据范围**限定可见的操作者（必含自己），忽略传入的 userId。
            // 这样部门管理员（DEPT_AND_BELOW）能看到下级的审计，而 SELF 档仍然只看自己。
            java.util.List<Long> scopeIds = orgService.visibleOwnerIds(cu);
            if (scopeIds.size() == 1) {
                qw.eq("user_id", scopeIds.get(0));
            } else {
                qw.in("user_id", scopeIds);
            }
        }
        // 租户条件：审计表已带 enterprise_id（V2025），平台管理员不加（需跨企业）。
        Long entFilter = resolveEnterpriseFilter();
        if (entFilter != null) {
            qw.eq("enterprise_id", entFilter);
        }
        qw.eq(action != null && !action.isBlank(), "action", action);
        qw.ge(start != null, "created_at", start);
        qw.le(end != null, "created_at", end);
        qw.orderByDesc("created_at");

        Page<AuditLog> result = auditLogMapper.selectPage(Page.of(page, size), qw);
        List<AuditLog> records = result.getRecords();

        // ── 把 id 翻译成名字 ──
        // 库里只存 id，直接返回会让界面显示成一串数字。本页涉及的三类都批量查一次，避免 N+1。
        // 既要查操作人，也要查「target 是用户 id」的记录（quota_change），合成一次批量查询
        java.util.LinkedHashSet<Long> idsToResolve = new java.util.LinkedHashSet<>();
        for (AuditLog l : records) {
            if (l.getUserId() != null) {
                idsToResolve.add(l.getUserId());
            }
            if (USER_ID_ACTIONS.contains(l.getAction())) {
                Long tid = parseLong(l.getTarget());
                if (tid != null) {
                    idsToResolve.add(tid);
                }
            }
        }
        List<Long> userIds = new java.util.ArrayList<>(idsToResolve);
        Map<Long, String> usernames = new java.util.HashMap<>();
        Map<Long, String> actorNames = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> row : auditLogMapper.selectUserNames(userIds)) {
                long id = ((Number) row.get("id")).longValue();
                usernames.put(id, str(row.get("username")));
                actorNames.put(id, displayName(row.get("nickname"), row.get("username")));
            }
        }

        // target 是节点 id 的动作（文件类）
        List<Long> nodeIds = records.stream()
                .filter(l -> NODE_ACTIONS.contains(l.getAction()))
                .map(l -> parseLong(l.getTarget()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> fileNames = new java.util.HashMap<>();
        if (!nodeIds.isEmpty()) {
            for (Map<String, Object> row : auditLogMapper.selectFileNames(nodeIds)) {
                fileNames.put(((Number) row.get("id")).longValue(), str(row.get("name")));
            }
        }

        // target 直接是用户名的动作（登录 / 用户管理）
        List<String> targetUsernames = records.stream()
                .filter(l -> USERNAME_ACTIONS.contains(l.getAction()))
                .map(AuditLog::getTarget)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        Map<String, String> nicknameByUsername = new java.util.HashMap<>();
        if (!targetUsernames.isEmpty()) {
            for (Map<String, Object> row : auditLogMapper.selectNicknamesByUsernames(targetUsernames)) {
                nicknameByUsername.put(str(row.get("username")), str(row.get("nickname")));
            }
        }

        List<AuditLogVO> list = records.stream()
                .map(log -> AuditLogVO.from(
                        log,
                        usernames.get(log.getUserId()),
                        actorDisplay(log, actorNames),
                        resolveTargetName(log, fileNames, actorNames, nicknameByUsername)))
                .toList();
        return Result.ok(PageResult.of(result.getTotal(), list));
    }

    /** target 是文件节点 id 的动作。 */
    private static final java.util.Set<String> NODE_ACTIONS = java.util.Set.of(
            "mkdir", "move", "rename", "delete", "delete_force", "restore", "download", "upload", "complete");

    /** target 直接是用户名的动作。 */
    private static final java.util.Set<String> USERNAME_ACTIONS = java.util.Set.of("login", "login_fail", "user_manage");

    /**
     * 目标展示名。
     *
     * <p>target 的语义随 action 变化：文件类是节点 id，登录类是用户名，配额类（quota_change）是用户 id。
     * 按 action 分派；解析不到就返回 null（VO 会回退成原始 target，不会显示成空白）。
     */
    private static String resolveTargetName(AuditLog log,
                                            Map<Long, String> fileNames,
                                            Map<Long, String> userNames,
                                            Map<String, String> nicknameByUsername) {
        String action = log.getAction() == null ? "" : log.getAction();
        String target = log.getTarget();
        if (target == null || target.isBlank()) {
            return null;
        }
        if (USERNAME_ACTIONS.contains(action)) {
            String nick = nicknameByUsername.get(target);
            return nick != null && !nick.isBlank() ? nick : target;
        }
        Long id = parseLong(target);
        if (id == null) {
            return null;
        }
        if (NODE_ACTIONS.contains(action)) {
            String name = fileNames.get(id);
            // 文件已被彻底删除时查不到名字，给个可读前缀，别直接甩数字
            return name != null && !name.isBlank() ? name : "文件 #" + id;
        }
        if (USER_ID_ACTIONS.contains(action)) {
            return userNames.get(id);
        }
        // 计费配置 / 增额申请：库里只有配置 id 与申请 id，给个可读前缀，别直接甩数字
        if ("billing_config".equals(action)) {
            return "计费配置 #" + id;
        }
        if ("billing_request".equals(action)) {
            return "增额申请 #" + id;
        }
        if ("user_manage".equals(action)) {
            return userNames.get(id);
        }
        return null;
    }

    /** 当前用户：从 SecurityContext 取（A 组 JwtAuthFilter 注入 CurrentUser），与 B 组 UploadController 一致。 */
    private CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
    /** 租户过滤值：null = 不加条件（平台管理员 / 无上下文）。 */
    private static Long resolveEnterpriseFilter() {
        try {
            if (com.company.cloud.common.tenant.TenantContext.isBypass()
                    || !com.company.cloud.common.tenant.TenantContext.isPresent()) {
                return null;
            }
            return com.company.cloud.common.tenant.TenantContext.require();
        } catch (Exception e) {
            return null;
        }
    }
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 展示名：nickname 优先，回退 username。 */
    private static String displayName(Object nickname, Object username) {
        String n = str(nickname);
        return n != null && !n.isBlank() ? n : str(username);
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
    /** target 是用户 id 的动作（配额调整）。 */
    private static final java.util.Set<String> USER_ID_ACTIONS = java.util.Set.of("quota_change");

    /**
     * 操作人展示名。
     *
     * <p>{@code user_id} 为空说明是**未登录**事件（登录失败、账号不存在），
     * 此时明确显示「(未登录)」，不要让界面出现空白让人以为数据丢了。
     */
    private static String actorDisplay(AuditLog log, Map<Long, String> actorNames) {
        if (log.getUserId() == null) {
            return "(未登录)";
        }
        String n = actorNames.get(log.getUserId());
        return n != null && !n.isBlank() ? n : "(已删除用户 " + log.getUserId() + ")";
    }
}