package com.company.cloud.files.stats.controller;

import com.company.cloud.auth.rbac.PermissionChecker;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.files.stats.dto.AdminStatsOverview;
import com.company.cloud.files.stats.dto.TopUser;
import com.company.cloud.files.stats.dto.TrafficPoint;
import com.company.cloud.files.stats.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端大盘统计（R-C09，契约前缀 /api/admin/stats，context-path 已配 /api）。
 *
 * <pre>
 *  - GET /api/admin/stats/overview          总览（平台管理员=全平台 / 企业管理员=本企业）
 *  - GET /api/admin/stats/top-users         用量 Top10 用户（同上范围）
 *  - GET /api/admin/stats/traffic?days=7    近 N 日上传/下载流量（1~90，默认 7，缺流量日补零）
 * </pre>
 *
 * <p><b>鉴权（2026-10-28 调整）</b>：原来整类打 {@code @RequireRole("admin")}，企业管理员
 * 一律 40301。但 {@code admin.dashboard.read} 本身就在企业管理员的 26 项默认权限里，
 * 所以改为按权限点判定，并对数据范围做企业隔离：
 * <ul>
 *   <li>{@code admin.dashboard.read} → 三个接口</li>
 *   <li>企业管理员的 SQL 带 {@code enterprise_id} 过滤；平台管理员不带（看全平台）</li>
 * </ul>
 * 不做企业隔离的后果：企业管理员会看到别家企业的用量与流量。
 *
 * <p>流量数据源为 audit_logs 的 upload / download 记录（detail.size）。
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    /** 大盘查看权限点。 */
    private static final String PERM_DASHBOARD_READ = "admin.dashboard.read";

    private final AdminStatsService adminStatsService;
    private final PermissionChecker permissionChecker;

    @GetMapping("/overview")
    public Result<AdminStatsOverview> overview(Authentication authentication) {
        CurrentUser cu = requireDashboard(authentication);
        return Result.ok(adminStatsService.overview(PermissionChecker.dataScopeEnterpriseId(cu)));
    }

    @GetMapping("/top-users")
    public Result<List<TopUser>> topUsers(Authentication authentication) {
        CurrentUser cu = requireDashboard(authentication);
        return Result.ok(adminStatsService.topUsers(PermissionChecker.dataScopeEnterpriseId(cu)));
    }

    @GetMapping("/traffic")
    public Result<List<TrafficPoint>> traffic(Authentication authentication,
                                             @RequestParam(defaultValue = "7") int days) {
        CurrentUser cu = requireDashboard(authentication);
        return Result.ok(adminStatsService.traffic(days,
                PermissionChecker.dataScopeEnterpriseId(cu)));
    }

    /** 校验登录 + 大盘权限，返回当前用户（供上层取数据范围）。 */
    private CurrentUser requireDashboard(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser cu)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        permissionChecker.require(cu, PERM_DASHBOARD_READ);
        return cu;
    }
}