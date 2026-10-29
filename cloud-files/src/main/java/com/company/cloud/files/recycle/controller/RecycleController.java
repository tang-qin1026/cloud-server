package com.company.cloud.files.recycle.controller;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.PageResult;
import com.company.cloud.common.result.Result;
import com.company.cloud.files.dir.dto.FileNodeVO;
import com.company.cloud.files.recycle.dto.RecycleItemVO;
import com.company.cloud.files.recycle.dto.RestoreRequest;
import com.company.cloud.files.recycle.service.RecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站接口（任务书 04 §5：R-C05 / R-C06）。
 *
 * <p><b>鉴权：</b>A 组 JWT，从 SecurityContext 取当前用户（与 B 组一致），无需 X-User-Id 头。
 */
@Tag(name = "回收站", description = "回收站列表、还原、彻底删除（C 组）")
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class RecycleController {

    /** 权限点校验：权限配置必须真实生效，不能只靠前端隐藏入口。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;
    private final RecycleService recycleService;

    @Operation(summary = "回收站列表（R-C05）：parent 空/0 返回顶层，parent>0 返回子项；按删除时间倒序；含 deletedAt/expireAt")
    @GetMapping("/trash")
    public Result<PageResult<RecycleItemVO>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long parent) {
        permissionChecker.require(cu(authentication), "recycle.read");
        return Result.ok(recycleService.list(currentUserId(authentication), page, size, parent));
    }

    @Operation(summary = "还原（R-C06）：未指定 targetParentId 回原位，指定则恢复到目标目录；重名自动改名")
    @PostMapping("/{id}/restore")
    public Result<FileNodeVO> restore(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody(required = false) RestoreRequest request) {
        Long targetParentId = request == null ? null : request.targetParentId();
        permissionChecker.require(cu(authentication), "recycle.restore");
        return Result.ok(recycleService.restore(currentUserId(authentication), id, targetParentId));
    }

    private CurrentUser cu(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser c) {
            return c;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    /** 当前用户 id：从 SecurityContext 取（A 组 JwtAuthFilter 注入 CurrentUser），与 B 组 UploadController 一致。 */
    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu.getId();
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
}