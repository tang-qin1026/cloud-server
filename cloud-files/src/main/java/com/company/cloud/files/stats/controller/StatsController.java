package com.company.cloud.files.stats.controller;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.files.stats.dto.StatsOverview;
import com.company.cloud.files.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计大盘接口（R-C10）。
 *
 * <p><b>鉴权：</b>A 组 JWT，从 SecurityContext 取当前用户（与 B 组一致），无需 X-User-Id 头。
 */
@Tag(name = "统计大盘", description = "个人存储统计概览（C 组）")
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    /** 权限点校验：权限配置必须真实生效。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;

    private final StatsService statsService;

    @Operation(summary = "存储概览（R-C10）：文件/目录/字节/今日新增/回收站/类型分布")
    @GetMapping("/overview")
    public Result<StatsOverview> overview(Authentication authentication) {
        permissionChecker.require(cu(authentication), "quota.read");
        return Result.ok(statsService.overview(currentUserId(authentication)));
    }

    /** 当前用户 id：从 SecurityContext 取（A 组 JwtAuthFilter 注入 CurrentUser），与 B 组 UploadController 一致。 */
    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu.getId();
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
    private CurrentUser cu(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser c) {
            return c;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
}