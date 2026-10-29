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

        // 批量补 username：本页出现的 user_id 一次查 users，避免 N+1
        List<Long> userIds = records.stream()
                .map(AuditLog::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> usernames = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> row : auditLogMapper.selectUsernames(userIds)) {
                usernames.put(((Number) row.get("id")).longValue(), (String) row.get("username"));
            }
        }

        List<AuditLogVO> list = records.stream()
                .map(log -> AuditLogVO.from(log, usernames.get(log.getUserId())))
                .toList();
        return Result.ok(PageResult.of(result.getTotal(), list));
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
}