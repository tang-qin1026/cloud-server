package com.company.cloud.files.dir.controller;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.files.dir.dto.BatchMoveRequest;
import com.company.cloud.files.dir.dto.DirListResponse;
import com.company.cloud.files.dir.dto.FileNodeVO;
import com.company.cloud.files.dir.dto.MkdirRequest;
import com.company.cloud.files.dir.dto.UpdateNodeRequest;
import com.company.cloud.files.dir.service.FileNodeService;
import com.company.cloud.files.recycle.service.RecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件管理接口（任务书 04 §5）。
 *
 * <p><b>鉴权：</b>A 组 JWT（Authorization: Bearer token），JwtAuthFilter 已把
 * CurrentUser 注入 SecurityContext，此处直接从 Authentication 取当前用户 id。
 * 前端无需再传 X-User-Id 请求头。
 */
@Tag(name = "文件管理", description = "目录树、重命名/移动、回收站入口（C 组）")
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileNodeController {

    /** 权限点校验：权限配置必须真实生效，不能只靠前端隐藏入口。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;
    private final FileNodeService fileNodeService;
    private final RecycleService recycleService;

    @Operation(summary = "列目录（R-C01）：分页 + 排序 + 面包屑；keyword 非空时全局搜索")
    @GetMapping
    public Result<DirListResponse> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") long parent,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String keyword) {
        permissionChecker.require(cu(authentication), "file.read");
        return Result.ok(fileNodeService.list(currentUserId(authentication), parent, page, size, sort, keyword));
    }

    @Operation(summary = "目录树：一次性返回当前用户全部目录（扁平 id+parentId 列表，前端自行建树）")
    @GetMapping("/tree")
    public Result<java.util.List<FileNodeVO>> tree(Authentication authentication) {
        permissionChecker.require(cu(authentication), "file.read");
        return Result.ok(fileNodeService.tree(currentUserId(authentication)));
    }

    @Operation(summary = "新建文件夹（R-C02）：同级重名自动改名")
    @PostMapping("/mkdir")
    public Result<FileNodeVO> mkdir(
            Authentication authentication,
            @Valid @RequestBody MkdirRequest request) {
        permissionChecker.require(cu(authentication), "file.upload");
        return Result.ok(fileNodeService.mkdir(currentUserId(authentication), request));
    }

    @Operation(summary = "重命名 / 移动（R-C03）：禁止移入自身子目录")
    @PatchMapping("/{id}")
    public Result<FileNodeVO> update(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateNodeRequest request) {
        permissionChecker.require(cu(authentication), "file.modify");
        return Result.ok(fileNodeService.update(currentUserId(authentication), id, request));
    }

    @Operation(summary = "批量移动：多选文件/目录一次移入同一目标目录，任一失败整体回滚")
    @PatchMapping("/batch-move")
    public Result<java.util.List<FileNodeVO>> batchMove(
            Authentication authentication,
            @Valid @RequestBody BatchMoveRequest request) {
        permissionChecker.require(cu(authentication), "file.modify");
        return Result.ok(fileNodeService.batchMove(currentUserId(authentication), request));
    }

    @Operation(summary = "删除（R-C04/R-C06）：默认入回收站；?force=1 彻底删除（级联物理删+释放配额）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            Authentication authentication,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int force) {
        if (force == 1) {
            recycleService.forceDelete(currentUserId(authentication), id);
        } else {
            fileNodeService.delete(currentUserId(authentication), id);
        }
        return Result.ok();
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
        // 未认证（正常会被 Security 拦 40103，此为兜底）
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
}
