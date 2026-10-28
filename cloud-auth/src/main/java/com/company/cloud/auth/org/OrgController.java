package com.company.cloud.auth.org;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 组织与权限接口（M3）。
 *
 * <p>路径分布在 {@code /me}、{@code /org}、{@code /users}、{@code /admin} 四个前缀下，
 * 故不在类上声明 {@code @RequestMapping}，各方法写全路径。
 *
 * <p><b>鉴权</b>：全部要求登录（Bearer）；租户边界与平台管理员判定在
 * {@link OrgService} 内统一处理，避免各接口各写一遍。
 */
@RestController
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    @GetMapping("/me/context")
    public Result<Map<String, Object>> meContext(Authentication authentication) {
        return Result.ok(orgService.meContext(currentUser(authentication)));
    }

    @GetMapping("/org/permissions")
    public Result<Map<String, Object>> permissions(Authentication authentication) {
        currentUser(authentication);   // 仅要求登录
        return Result.ok(orgService.permissions());
    }

    @GetMapping("/org/roles")
    public Result<List<Map<String, Object>>> roles(Authentication authentication) {
        currentUser(authentication);
        return Result.ok(orgService.roles());
    }

    @GetMapping("/org/depts/tree")
    public Result<Map<String, Object>> deptTree(
            Authentication authentication,
            @RequestParam(required = false) String status) {
        currentUser(authentication);
        return Result.ok(orgService.deptTree(status));
    }

    @GetMapping("/users")
    public Result<Map<String, Object>> members(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser(authentication);
        return Result.ok(orgService.members(keyword, status, deptId, page, size));
    }

    @GetMapping("/admin/enterprises")
    public Result<Map<String, Object>> enterprises(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        currentUser(authentication);
        return Result.ok(orgService.enterprises(status, page, size));
    }

    /** 从 SecurityContext 取当前用户（{@code JwtAuthFilter} 已注入 {@link CurrentUser}）。 */
    private CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
    // ── 成员配置（部门 / 角色 / 数据权限）────────────────────────

    /**
     * 更新成员的组织配置。三个字段一起提交，**立即生效**（不需要成员重新登录）。
     */
    @PutMapping("/users/{id}")
    public Result<Void> updateMember(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody UpdateMemberRequest req) {
        orgService.updateMember(currentUser(authentication), id,
                req.deptId(), req.dataScope(), req.roleIds());
        return Result.ok();
    }

    // ── 资源分配 ───────────────────────────────────────────────

    /** 企业资源池（规格 / 持有人 / 是否已分配）。 */
    @GetMapping("/org/resources")
    public Result<Map<String, Object>> resources(Authentication authentication) {
        return Result.ok(orgService.resources(currentUser(authentication)));
    }

    /** 某成员当前持有的资源。 */
    @GetMapping("/users/{id}/resources")
    public Result<Map<String, Object>> memberResources(
            Authentication authentication,
            @PathVariable long id) {
        return Result.ok(orgService.memberResources(currentUser(authentication), id));
    }

    /**
     * **整体覆盖**成员持有的资源列表；差集即分配 / 收回（收回回到企业主管理员）。
     */
    @PutMapping("/users/{id}/resources")
    public Result<Void> assignResources(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody AssignResourcesRequest req) {
        orgService.assignResources(currentUser(authentication), id, req.resourceIds());
        return Result.ok();
    }

    // ── 请求体 ─────────────────────────────────────────────────

    /**
     * 成员配置入参。
     *
     * @param deptId   部门 id；{@code null} = 不分配部门（挂在企业根）
     * @param dataScope 数据范围：{@code SELF} / {@code DEPT_AND_BELOW} / {@code ALL}
     * @param roleIds  角色 id 列表；{@code null} = 不改角色，空数组 = 清空角色
     */
    public record UpdateMemberRequest(Long deptId, String dataScope, List<Long> roleIds) {
    }

    /** 资源分配入参：传"最终应当持有的资源 id 列表"。 */
    public record AssignResourcesRequest(List<Long> resourceIds) {
    }
    // ── 数据范围 ───────────────────────────────────────────────

    /**
     * 按数据范围返回"我 能看到的他人"（用于文件归属筛选下拉）。
     *
     * <p>只体现**范围**；是否真的能读他人文件还取决于 {@code file.other.read} 功能权限。
     */
    @GetMapping("/files/others/owners")
    public Result<Map<String, Object>> visibleOwners(Authentication authentication) {
        return Result.ok(orgService.visibleOwners(currentUser(authentication)));
    }
    // ── 部门（增 / 改 / 删）────────────────────────────────────

    @PostMapping("/org/depts")
    public Result<Map<String, Object>> createDept(
            Authentication authentication,
            @RequestBody DeptRequest req) {
        return Result.ok(orgService.createDept(currentUser(authentication),
                req.name(), req.parentId(), req.sortNo()));
    }

    @PutMapping("/org/depts/{id}")
    public Result<Void> updateDept(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody DeptRequest req) {
        orgService.updateDept(currentUser(authentication), id,
                req.name(), req.parentId(), req.sortNo());
        return Result.ok();
    }

    @DeleteMapping("/org/depts/{id}")
    public Result<Void> deleteDept(Authentication authentication, @PathVariable long id) {
        orgService.deleteDept(currentUser(authentication), id);
        return Result.ok();
    }

    // ── 角色权限点 ─────────────────────────────────────────────

    @GetMapping("/org/roles/{id}/permissions")
    public Result<Map<String, Object>> rolePermissions(
            Authentication authentication,
            @PathVariable long id) {
        return Result.ok(orgService.rolePermissions(currentUser(authentication), id));
    }

    /** 整体覆盖角色权限点。企业角色不可授予平台级权限点。 */
    @PutMapping("/org/roles/{id}/permissions")
    public Result<Void> setRolePermissions(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody RolePermissionsRequest req) {
        orgService.setRolePermissions(currentUser(authentication), id, req.codes());
        return Result.ok();
    }

    // ── 请求体 ─────────────────────────────────────────────────

    /**
     * 部门入参。
     *
     * @param name     部门名（新建必填；修改时为空表示不改名）
     * @param parentId 父部门 id；{@code null} 或 0 = 顶层
     * @param sortNo   排序，可空（默认 0）
     */
    public record DeptRequest(String name, Long parentId, Integer sortNo) {
    }

    /** 角色权限入参：传"最终应当拥有的权限点 code 列表"（整体覆盖）。 */
    public record RolePermissionsRequest(List<String> codes) {
    }
    // ── 角色 CRUD ──────────────────────────────────────────────

    /** 新建自定义角色。code 建后不可改，name 可改。 */
    @PostMapping("/org/roles")
    public Result<Map<String, Object>> createRole(
            Authentication authentication,
            @RequestBody RoleRequest req) {
        return Result.ok(orgService.createRole(currentUser(authentication), req.code(), req.name()));
    }

    /** 改角色名（code 不变，权限绑定不受影响）。 */
    @PutMapping("/org/roles/{id}")
    public Result<Void> renameRole(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody RoleRequest req) {
        orgService.renameRole(currentUser(authentication), id, req.name());
        return Result.ok();
    }

    /** 删除自定义角色（内置角色不可删；仍有成员绑定不可删）。 */
    @DeleteMapping("/org/roles/{id}")
    public Result<Void> deleteRole(Authentication authentication, @PathVariable long id) {
        orgService.deleteRole(currentUser(authentication), id);
        return Result.ok();
    }

    /** 角色入参：新建时 code + name 都必填；改名时只用 name。 */
    public record RoleRequest(String code, String name) {
    }
}