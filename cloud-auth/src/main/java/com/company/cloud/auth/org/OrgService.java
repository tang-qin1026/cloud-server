package com.company.cloud.auth.org;

import com.company.cloud.auth.rbac.EnterpriseAdminPermissions;

import com.company.cloud.auth.org.mapper.OrgQueryMapper;
import com.company.cloud.auth.org.mapper.OrgWriteMapper;
import org.springframework.transaction.annotation.Transactional;
import com.company.cloud.auth.rbac.mapper.RbacQueryMapper;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织与权限查询（M3 只读部分）。
 *
 * <p><b>租户边界统一在这里决策</b>，不让 Controller 各自判断：
 * <ul>
 *   <li>平台管理员（{@link TenantContext#isBypass()}）→ {@code entId = null}，跨企业</li>
 *   <li>企业 / 个人域用户 → {@code entId = TenantContext.require()}</li>
 * </ul>
 *
 * <p>数据范围（{@code data_scope}）在本类只做**展示**；拦截真正落到 SQL 是在各业务查询里
 * 拼 {@code dept.path LIKE} / {@code owner_id IN (...)}（见实现文档 v3 §5.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgService {

    private final OrgQueryMapper orgMapper;
    private final OrgWriteMapper orgWriteMapper;
    private final RbacQueryMapper rbacMapper;

    /**
     * 企业成员同步端口（实现由 cloud-portal 提供）。
     *
     * <p>用 {@link ObjectProvider} 而非直接注入：cloud-auth 不依赖 cloud-portal，
     * 若某次部署没带 cloud-portal（或单测环境），这里拿不到实现也不该让整个应用起不来。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<
            com.company.cloud.auth.portal.EnterpriseMemberSyncPort> memberSyncPort;
    private final com.company.cloud.auth.rbac.RbacService rbacService;

    // ── 当前用户上下文 ───────────────────────────────────────────

    /**
     * {@code GET /me/context}：一次拿到「我是谁、属于哪、能做什么」。
     *
     * <p>前端据此渲染菜单与按钮，不再自己探测角色。
     */
    public Map<String, Object> meContext(CurrentUser cu) {
        Long entId = tenantIdForRead();

        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", cu.getId());
        me.put("username", cu.getUsername());
        me.put("role", cu.getRole());

        Map<String, Object> enterprise = null;
        if (entId != null && entId != 0L) {
            Map<String, Object> e = orgMapper.selectEnterpriseById(entId);
            if (e != null) {
                // ⚠️ 不能用 Map.of：expireAt / rejectReason 等列**允许为 NULL**，
                // Map.of 遇到 null 值会抛 NPE，表现为接口 50000（真实踩过）。
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", e.get("id"));
                em.put("code", e.get("code"));
                em.put("name", e.get("name"));
                em.put("status", e.get("status"));
                em.put("expireAt", e.get("expire_at"));
                enterprise = em;
            }
        }
        me.put("enterprise", enterprise);
        me.put("enterpriseId", entId);
        me.put("isEnterpriseAdmin", cu.isEnterpriseAdmin());
        me.put("isPlatformAdmin", cu.isAdmin() && !cu.isEnterpriseAdmin());

        // 部门与档位
        List<Map<String, Object>> roles = rolesOf(cu);
        me.put("roles", roles);
        me.put("dataScope", currentDataScope(cu));
        me.put("permissions", effectivePermissions(cu));
        return me;
    }

    // ── 权限点字典 ───────────────────────────────────────────────

    /** {@code GET /org/permissions}：40 个权限点，按 8 个分组。 */
    public Map<String, Object> permissions() {
        List<Map<String, Object>> all = orgMapper.selectAllPermissions();
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> p : all) {
            String g = String.valueOf(p.get("group_code"));
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(Map.of(
                    "code", p.get("code"),
                    "name", p.get("name"),
                    "highRisk", Boolean.TRUE.equals(p.get("high_risk"))));
        }
        return Map.of("total", all.size(), "groups", groups);
    }

    // ── 角色 ───────────────────────────────────────────────────

    /**
     * {@code GET /org/roles}：**本企业**角色。
     *
     * <p>平台管理员调用会 40000「需要企业上下文」：它没有"某一家企业"的语境，
     * 之前返回所有企业的角色（实测 6 条，dept_admin/member 各 3 份），
     * 前端做下拉/筛选时会出现重名重复项，属误导。
     *
     * <p>需要看某家企业的角色时，应以该企业管理员身份调用（或用企业详情页）。
     */
    public List<Map<String, Object>> roles(com.company.cloud.auth.security.CurrentUser cu) {
        requirePerm(cu, "role.read");
        Long entId = tenantIdForRead();
        if (entId == null || entId == 0L) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "角色列表需要企业上下文：平台管理员/个人域账号不适用，请用企业账号访问");
        }
        // 企业首次访问时惰性创建 4 个内置角色（靠 uk_role_code 防并发生成两份）。
        // 这是文档 §3.3-3 要求的行为，也是"角色由产品侧管理、门户不存角色"的落地方式。
        if (entId != null && entId != 0L) {
            rbacService.ensureEnterpriseRoles(entId);
        }
        List<Map<String, Object>> rows = orgMapper.selectRoles(entId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("builtin", Boolean.TRUE.equals(r.get("builtin")));
            m.put("status", r.get("status"));
            m.put("permissionCount", r.get("perm_count"));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> rolesOf(CurrentUser cu) {
        List<Map<String, Object>> all = rolesOfEnterpriseOrAll(cu);
        Set<Long> mine = new LinkedHashSet<>(rgbs(orgMapper.selectUserRoleIds(cu.getId())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Object id = r.get("id");
            if (id != null && mine.contains(((Number) id).longValue())) {
                out.add(Map.of("id", id, "code", r.get("code"), "name", r.get("name")));
            }
        }
        return out;
    }

    // ── 部门树 ─────────────────────────────────────────────────

    /**
     * {@code GET /org/depts/tree}：扁平列表 + {@code path}，前端自行建树。
     *
     * <p>返回扁平的 {@code id/parentId} 而不是嵌套结构：前端建树更灵活，
     * 且分页/搜索时不用反复展开。
     */
    public Map<String, Object> deptTree(com.company.cloud.auth.security.CurrentUser cu, String status) {
        requirePerm(cu, "org.dept.read");
        Long entId = tenantIdForRead();
        List<Map<String, Object>> rows = orgMapper.selectDepts(entId, status);

        // 先转成可变节点（LinkedHashMap，要往里塞 children），保证顺序与 SQL 的 path/sort 一致
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> flat = new ArrayList<>(rows.size());
        for (Map<String, Object> d : rows) {
            Map<String, Object> node = new LinkedHashMap<>();
            Object idObj = d.get("id");
            long id = idObj instanceof Number n ? n.longValue() : 0L;
            Object pidObj = d.get("parent_id");
            Long parentId = pidObj instanceof Number pn ? pn.longValue() : null;
            node.put("id", idObj);
            node.put("parentId", parentId);
            node.put("name", d.get("name"));
            node.put("path", d.get("path"));
            node.put("status", d.get("status"));
            node.put("sortNo", d.get("sort_no"));
            node.put("children", new ArrayList<Map<String, Object>>());
            byId.put(id, node);
            flat.add(node);
        }

        // 挂父子：parent 不在结果集里（被 status 过滤掉）的按根处理，避免出现"孤儿"不可见
        List<Map<String, Object>> tree = new ArrayList<>();
        for (Map<String, Object> node : byId.values()) {
            Object pidObj = node.get("parentId");
            Long pid = pidObj instanceof Number n ? n.longValue() : null;
            Map<String, Object> parent = (pid == null || pid == 0L) ? null : byId.get(pid);
            if (parent == null) {
                tree.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        }
        // tree = 嵌套（前端树组件直接用）；list = 扁平（保留兼容，字段与之前一致）
        return Map.of("total", flat.size(), "tree", tree, "list", flat);
    }
    // ── 成员列表 ───────────────────────────────────────────────

    /** {@code GET /users}：成员列表（分页）。 */
    public Map<String, Object> members(com.company.cloud.auth.security.CurrentUser cu,
                                       String keyword, String status, Long deptId, int page, int size) {
        requirePerm(cu, "org.member.read");
        Long entId = tenantIdForRead();
        // 企业管理员看成员列表前，先从门户补一遍本企业成员。
        //
        // 不补的话列表里只有"登录过的人"：门户注册的员工没登录过 → 本地没有影子行 →
        // 企业管理员会看到"成员数 1"，而门户显示 3，看起来像功能坏了。
        syncMembersIfEnterpriseAdmin(entId);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        long total = orgMapper.countMembers(entId, keyword, status, deptId);
        List<Map<String, Object>> rows = orgMapper.selectMembers(
                entId, keyword, status, deptId, s, (p - 1) * s);

        // 批量取"用户 → RBAC 角色"，一次 IN 查询，避免每人一次往返
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> u : rows) {
            Object id = u.get("id");
            if (id instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        Map<Long, List<Map<String, Object>>> rolesByUser = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> r : orgMapper.selectRolesByUserIds(ids)) {
                Object uid = r.get("user_id");
                if (uid instanceof Number n) {
                    rolesByUser.computeIfAbsent(n.longValue(), k -> new ArrayList<>())
                            .add(Map.of(
                                    "id", r.get("role_id"),
                                    "code", r.get("code"),
                                    "name", r.get("name")));
                }
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> u : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object idObj = u.get("id");
            long uid = idObj instanceof Number n ? n.longValue() : 0L;
            m.put("id", idObj);
            m.put("username", u.get("username"));
            m.put("nickname", u.get("nickname"));
            m.put("enterpriseId", u.get("enterprise_id"));
            m.put("deptId", u.get("dept_id"));
            m.put("deptName", u.get("dept_name"));
            m.put("enterpriseRole", u.get("enterprise_role"));
            m.put("dataScope", upperScope(str(u.get("data_scope"))));
            m.put("status", u.get("status"));
            m.put("lastLoginAt", u.get("last_login_at"));
            // RBAC 角色（注意与 role = user/admin 的账号类型、enterpriseRole = 0/1 区分开）
            List<Map<String, Object>> rs = rolesByUser.getOrDefault(uid, List.of());
            m.put("roles", rs);
            // 兼容单角色写法：取第一个角色 id，前端不想改数组也能用
            m.put("roleId", rs.isEmpty() ? null : rs.get(0).get("id"));
            list.add(m);
        }
        return Map.of("total", total, "list", list);
    }

    // ── 平台管理员：企业列表 ─────────────────────────────────────

    // GET /admin/enterprises 的本地查表实现已下线（2026-10-29，docs/29）：
    // 门户的企业在我们库里没有完整数据（只有登录时补的影子企业），
    // 改成由 cloud-portal 的 AdminEnterpriseDirectoryController 调门户的企业目录接口。
    // 保留本方法会让人误以为这里才是企业列表的数据源。

    // ── 内部工具 ───────────────────────────────────────────────

    /** 读操作的租户 id：平台管理员返回 null（跨企业），其余返回当前企业。 */
    private Long tenantIdForRead() {
        if (TenantContext.isBypass()) {
            return null;
        }
        return TenantContext.require();
    }

    private void requirePlatformAdmin() {
        if (!TenantContext.isBypass()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可执行该操作");
        }
    }

    private String currentDataScope(CurrentUser cu) {
        if (TenantContext.isBypass()) {
            return "ALL";
        }
        // 以库里的 data_scope 为准；CurrentUser 不含该字段，故从成员列表取本人一行
        List<Map<String, Object>> rows = orgMapper.selectMembers(
                TenantContext.require(), cu.getUsername(), null, null, 1, 0);
        for (Map<String, Object> r : rows) {
            if (String.valueOf(r.get("id")).equals(String.valueOf(cu.getId()))) {
                return upperScope(str(r.get("data_scope")));
            }
        }
        return "SELF";
    }

    /**
     * 功能权限点。
     *
     * <p>平台管理员 → 全量；企业管理员 → {@link EnterpriseAdminPermissions#CODES} ∪ 角色权限；
     * 其余 → 多角色并集。
     *
     * <p>⚠️ 企业管理员**不能**直接拿 {@code selectAllPermCodes()}：
     * 那会连带授予 {@code release_sys}（发全平台版本、改系统配置、读内网密钥）、
     * {@code admin_user}（管所有企业用户）、{@code admin_billing}（全平台台账与审批）。
     */
    private List<String> effectivePermissions(CurrentUser cu) {
        if (cu.isAdmin()) {
            return rbacMapper.selectAllPermCodes();
        }
        java.util.LinkedHashSet<String> perms =
                new java.util.LinkedHashSet<>(rbacMapper.selectPermCodes(cu.getId()));
        if (cu.isEnterpriseAdmin()) {
            perms.addAll(EnterpriseAdminPermissions.CODES);
        }
        return new java.util.ArrayList<>(perms);
    }

    private static String upperScope(String raw) {
        if (raw == null) {
            return "SELF";
        }
        return switch (raw) {
            case "self" -> "SELF";
            case "dept" -> "DEPT";
            case "dept_below" -> "DEPT_BELOW";
            case "dept_subtree" -> "DEPT_AND_BELOW";
            case "all" -> "ALL";
            default -> raw.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<Long> rgbs(List<Long> in) {
        return in == null ? List.of() : in;
    }
    // ── 成员配置：部门 + 角色 + 数据权限（三件套）─────────────────

    /**
     * 更新成员的组织配置。
     *
     * <p>三个字段**一起提交**：角色决定功能权限，数据权限决定可见范围，两者互不影响。
     * 配置立即生效（授权数据不在 token 里，下次请求即按新配置判定）。
     *
     * @param roleIds {@code null} 表示不改角色；空列表表示清空角色
     */
    @Transactional
    public void updateMember(CurrentUser cu, long userId,
                             Long deptId, String dataScope, List<Long> roleIds) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "org.member.manage");

        // 部门必须属于本企业（跨企业部门 id 一律拒绝，而不是静默写成 null）
        if (deptId != null && orgWriteMapper.countDeptInEnterprise(deptId, entId) == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不属于本企业：" + deptId);
        }

        // 企业管理员的 data_scope 固定 all：他的身份就是"本企业全权"。
        // 若允许改成 self，会出现"企业管理员在成员列表只看到自己 / 文件只看到自己的"
        // 这种看起来像故障的状态（真实发生过：前端在成员管理页保存时把管理员改成了仅个人）。
        Integer er = orgMapper.selectEnterpriseRole(userId, entId);
        String dbScope = (er != null && er == 1) ? "all" : normalizeScope(dataScope);
        if (er != null && er == 1 && !"all".equalsIgnoreCase(normalizeScope(dataScope))) {
            log.info("[org] 企业管理员 data_scope 强制为 all userId={} 原请求={}", userId, dataScope);
        }

        int n = orgWriteMapper.updateMemberScope(userId, entId, deptId, dbScope);
        if (n == 0) {
            // 归属条件没过：要么用户不存在，要么不属于本企业 —— 统一按不存在处理
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        if (roleIds != null) {
            List<Long> allowed = orgWriteMapper.selectRoleIdsOfEnterprise(entId);
            for (Long rid : roleIds) {
                if (rid == null || !allowed.contains(rid)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "角色不属于本企业：" + rid);
                }
            }
            orgWriteMapper.clearUserRoles(userId);
            if (!roleIds.isEmpty()) {
                orgWriteMapper.addUserRoles(userId, roleIds);
            }
        }
    }

    // ── 资源分配 ───────────────────────────────────────────────

    /** {@code GET /org/resources}：企业资源池。 */
    public Map<String, Object> resources(CurrentUser cu) {
        long entId = requireEnterpriseContext();
        requireAnyPerm(cu, "org.member.read", "org.member.resource");
        List<Map<String, Object>> rows = orgWriteMapper.selectResources(entId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("skuId", r.get("sku_id"));
            m.put("quotaGb", r.get("quota_gb"));
            m.put("holderUserId", r.get("holder_user_id"));
            m.put("assignedAt", r.get("assigned_at"));
            out.add(m);
        }
        return Map.of("total", out.size(), "list", out);
    }

    /** {@code GET /users/{id}/resources}：某成员当前持有的资源。 */
    public Map<String, Object> memberResources(CurrentUser cu, long userId) {
        long entId = requireEnterpriseContext();
        requireAnyPerm(cu, "org.member.read", "org.member.resource");
        Set<Long> held = new LinkedHashSet<>(orgWriteMapper.selectResourceIdsHeldBy(entId, userId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : orgWriteMapper.selectResources(entId)) {
            Object idObj = r.get("id");
            if (idObj != null && held.contains(((Number) idObj).longValue())) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", idObj);
                mm.put("skuId", r.get("sku_id"));
                mm.put("quotaGb", r.get("quota_gb"));
                list.add(mm);
            }
        }
        return Map.of("total", list.size(), "list", list);
    }

    /**
     * {@code PUT /users/{id}/resources}：**整体覆盖**该成员的资源持有列表。
     *
     * <p>差集即动作：新列表里有、原本没有 → 分配；原本有、新列表没有 → 收回。
     * 收回不是删除资源，而是把持有人改回**企业主管理员**
     * （资源归人不归企业，且 {@code holder_user_id} 是 NOT NULL，所以不存在"无主"状态）。
     */
    @Transactional
    public void assignResources(CurrentUser cu, long userId, List<Long> resourceIds) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "org.member.resource");

        Long targetEnt = orgMapper.selectUserEnterpriseId(userId);
        if (targetEnt == null || targetEnt != entId) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        List<Long> want = resourceIds == null ? List.of() : resourceIds;
        if (!want.isEmpty()
                && orgWriteMapper.countResourcesInEnterprise(want, entId) != want.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在不属于本企业的资源");
        }

        Long owner = orgWriteMapper.selectEnterpriseOwner(entId);
        // 兜底：企业尚未回填 owner 时，以企业 id 兜不住，直接报错更安全
        if (owner == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "企业未设置主管理员（owner_user_id 为空）");
        }

        Set<Long> wantSet = new LinkedHashSet<>(want);
        Set<Long> heldSet = new LinkedHashSet<>(orgWriteMapper.selectResourceIdsHeldBy(entId, userId));

        for (Long rid : wantSet) {
            if (!heldSet.contains(rid)) {
                orgWriteMapper.updateHolder(rid, entId, userId, owner);
            }
        }
        for (Long rid : heldSet) {
            if (!wantSet.contains(rid)) {
                orgWriteMapper.updateHolder(rid, entId, owner, owner);   // 收回 → 回到主管理员
            }
        }
    }

    // ── 权限与租户前置校验 ─────────────────────────────────────

    /**
     * 写操作必须落在**具体企业**里。
     *
     * <p>平台管理员与个人域账号都不能走组织写接口：前者会误改别家企业，
     * 后者根本没有企业。
     */
    private long requireEnterpriseContext() {
        if (TenantContext.isBypass()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口需要企业上下文：平台管理员/个人域账号不适用，请用企业账号或企业管理端访问");
        }
        long ent = TenantContext.require();
        if (ent == 0L) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口需要企业上下文：个人域账号不适用，请用企业账号访问");
        }
        return ent;
    }

    private void requirePerm(CurrentUser cu, String code) {
        if (cu.isAdmin()) {
            return;   // 平台管理员：全平台，直接放行
        }
        // 企业管理员不再无条件放行：按默认集合 + 角色并集判定，
        // 否则任一企业管理员都能调用平台级接口（发版本 / 改系统配置 / 管全体用户）。
        if (!effectivePermissions(cu).contains(code)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少权限：" + code);
        }
    }

    private void requireAnyPerm(CurrentUser cu, String... codes) {
        if (cu.isAdmin()) {
            return;
        }
        List<String> mine = effectivePermissions(cu);
        for (String c : codes) {
            if (mine.contains(c)) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "缺少权限：" + String.join(" / ", codes));
    }

    /** 对外档位名 → 库内小写值（与 V2025 的 CHECK 约束一致）。 */
    private static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "self";
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SELF" -> "self";
            case "DEPT" -> "dept";
            case "DEPT_BELOW" -> "dept_below";
            case "DEPT_AND_BELOW", "DEPT_SUBTREE" -> "dept_subtree";
            case "ALL" -> "all";
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "数据范围非法（可用：SELF / DEPT / DEPT_BELOW / DEPT_AND_BELOW / ALL）：" + raw);
        };
    }
    // ── 数据范围：可见的"他人" ──────────────────────────────────

    /**
     * {@code GET /files/others/owners}：当前用户按**数据范围**能看到哪些人。
     *
     * <p>语义（实现文档 §5.2）：
     * <ul>
     *   <li>{@code SELF} → 空列表（看不到任何他人）</li>
     *   <li>{@code DEPT_AND_BELOW} → 本部门 + 所有下级部门的成员，**不含兄弟与上级**</li>
     *   <li>{@code ALL} → 本企业全部成员</li>
     * </ul>
     *
     * <p><b>注意这是"范围"而不是"权限"</b>：即使范围是 ALL，要真正查看他人文件，
     * 仍需 {@code file.other.read} 功能权限。两个闸门是独立维度。
     */
    public Map<String, Object> visibleOwners(CurrentUser cu) {
        long entId = requireEnterpriseContext();
        String scope = currentDataScope(cu);          // 对外展示用（大写）
        // ⚠️ SQL 里比较的是**库内小写值**（self / dept_subtree / all）。
        // 之前直接把大写值传进 mapper，导致 <if test="scope == 'dept_subtree'"> 永远为假，
        // 部门过滤被整段跳过、退化成"全企业可见"（真实踩过，已由 [4] 用例暴露）。
        String dbScope = switch (scope) {
            case "ALL" -> "all";
            case "DEPT" -> "dept";
            case "DEPT_BELOW" -> "dept_below";
            case "DEPT_AND_BELOW" -> "dept_subtree";
            default -> "self";
        };

        if ("SELF".equals(scope)) {
            return Map.of("scope", scope, "total", 0, "list", List.of());
        }

        // dept / dept_below / dept_subtree 都需要本人的部门 id 与物化路径；
        // all 两者都不用（只看企业维度）。
        String myPath = null;
        Long myDeptId = null;
        if (!"all".equals(dbScope)) {
            Map<String, Object> dp = orgMapper.selectUserDeptPath(cu.getId());
            if (dp != null) {
                Object d = dp.get("dept_id");
                myDeptId = d instanceof Number n ? n.longValue() : null;
                myPath = str(dp.get("path"));
            }
        }

        List<Map<String, Object>> rows =
                orgMapper.selectVisibleOwners(entId, cu.getId(), dbScope, myPath, myDeptId);
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> u : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.get("id"));
            m.put("username", u.get("username"));
            m.put("nickname", u.get("nickname"));
            m.put("deptId", u.get("dept_id"));
            m.put("deptName", u.get("dept_name"));
            list.add(m);
        }
        return Map.of("scope", scope, "total", list.size(), "list", list);
    }
    /**
     * 数据范围内可见的 {@code owner_id} 集合（**必含自己**）。
     *
     * <p>供业务查询拼 {@code owner_id IN (...)}：文件列表、审计、账单等都复用它，
     * 保证"数据范围"只有一处实现。
     *
     * <p>返回 {@code List} 而不是直接拼 SQL：调用方用 MyBatis 的参数绑定，
     * 不存在注入风险，也便于单测。
     */
    public List<Long> visibleOwnerIds(CurrentUser cu) {
        List<Long> ids = new ArrayList<>();
        ids.add(cu.getId());

        // 平台管理员：保持既有行为（不按范围收窄）
        if (TenantContext.isBypass() || !TenantContext.isPresent()) {
            return ids;
        }
        long ent = TenantContext.require();
        if (ent == 0L) {
            return ids;                       // 个人域：只看自己
        }

        String scope = currentDataScope(cu);
        if ("SELF".equals(scope)) {
            return ids;
        }
        String dbScope = switch (scope) {
            case "ALL" -> "all";
            case "DEPT" -> "dept";
            case "DEPT_BELOW" -> "dept_below";
            case "DEPT_AND_BELOW" -> "dept_subtree";
            default -> "self";
        };
        String myPath = null;
        Long myDeptId = null;
        if (!"all".equals(dbScope)) {
            Map<String, Object> dp = orgMapper.selectUserDeptPath(cu.getId());
            if (dp != null) {
                Object d = dp.get("dept_id");
                myDeptId = d instanceof Number n ? n.longValue() : null;
                myPath = str(dp.get("path"));
            }
        }
        for (Map<String, Object> u : orgMapper.selectVisibleOwners(
                ent, cu.getId(), dbScope, myPath, myDeptId)) {
            Object id = u.get("id");
            if (id instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }
    // ── 部门 CRUD（物化路径维护是重点）───────────────────────────

    /**
     * 新建部门。{@code path = 父.path + 自己id + '/'}，根的 path 为 {@code /id/}。
     */
    @Transactional
    public Map<String, Object> createDept(CurrentUser cu, String name, Long parentId, Integer sortNo) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "org.dept.manage");
        String deptName = requireName(name);
        long pid = parentId == null ? 0L : parentId;

        String parentPath = "/";
        if (pid > 0) {
            Map<String, Object> parent = orgWriteMapper.selectDeptInEnterprise(pid, entId);
            if (parent == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "父部门不属于本企业：" + pid);
            }
            parentPath = str(parent.get("path"));
        }
        if (orgWriteMapper.countSiblingName(entId, pid, deptName, 0L) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同层已存在同名部门：" + deptName);
        }

        // 先插入占位 path，拿到自增 id 后再回填（path 含自身 id）
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entId", entId);
        row.put("parentId", pid == 0 ? null : pid);
        row.put("name", deptName);
        row.put("tmpPath", "/tmp/");
        row.put("sortNo", sortNo == null ? 0 : sortNo);
        orgWriteMapper.insertDept(row);
        Object newId = row.get("id");
        if (!(newId instanceof Number n)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "部门创建后未取得 id");
        }
        long selfId = n.longValue();
        String path = (parentPath.endsWith("/") ? parentPath : parentPath + "/") + selfId + "/";
        orgWriteMapper.updateDeptPath(selfId, entId, path);

        return Map.of("id", selfId, "path", path);
    }

    /**
     * 修改部门（改名 / 移动父级 / 排序）。
     *
     * <p><b>移动时必须级联改写子孙 path</b>：否则子树的 path 仍指向旧父级，
     * 数据范围 {@code dept_subtree} 用 LIKE 前缀匹配会漏掉整个子树 —— 这是静默的越权/漏权。
     */
    @Transactional
    public void updateDept(CurrentUser cu, long deptId, String name, Long parentId, Integer sortNo) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "org.dept.manage");

        Map<String, Object> self = orgWriteMapper.selectDeptInEnterprise(deptId, entId);
        if (self == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不属于本企业：" + deptId);
        }
        String oldPath = str(self.get("path"));
        long oldParent = self.get("parent_id") == null ? 0L : ((Number) self.get("parent_id")).longValue();
        String deptName = (name == null || name.isBlank()) ? str(self.get("name")) : requireName(name);
        long newParent = parentId == null ? oldParent : parentId;

        // 不能挂到自己或自己的子孙下（否则 path 会成环）
        if (newParent == deptId || newParent > 0) {
            Map<String, Object> np = newParent == 0 ? null : orgWriteMapper.selectDeptInEnterprise(newParent, entId);
            if (newParent > 0 && np == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "父部门不属于本企业：" + newParent);
            }
            if (np != null && str(np.get("path")).startsWith(oldPath)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能把部门移动到自己的子部门下");
            }
        }
        if (orgWriteMapper.countSiblingName(entId, newParent, deptName, deptId) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同层已存在同名部门：" + deptName);
        }

        orgWriteMapper.updateDept(deptId, entId, deptName, newParent == 0 ? null : newParent,
                sortNo == null ? 0 : sortNo);

        if (newParent != oldParent) {
            String parentPath = "/";
            if (newParent > 0) {
                parentPath = str(orgWriteMapper.selectDeptInEnterprise(newParent, entId).get("path"));
            }
            String newPath = (parentPath.endsWith("/") ? parentPath : parentPath + "/") + deptId + "/";
            orgWriteMapper.updateDeptPath(deptId, entId, newPath);
            // 级联：子孙 path 里把 oldPath 前缀换成 newPath
            orgWriteMapper.rewriteDescendantPaths(entId, oldPath, newPath, deptId);
        }
    }

    /** 删除部门：必须无子部门、无成员，否则先迁走。 */
    @Transactional
    public void deleteDept(CurrentUser cu, long deptId) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "org.dept.manage");
        if (orgWriteMapper.selectDeptInEnterprise(deptId, entId) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不属于本企业：" + deptId);
        }
        int child = orgWriteMapper.countChildDepts(entId, deptId);
        if (child > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在 " + child + " 个子部门，请先删除或移走");
        }
        int members = orgWriteMapper.countMembersInDept(entId, deptId);
        if (members > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门下还有 " + members + " 名成员，请先调整其部门");
        }
        orgWriteMapper.deleteDept(deptId, entId);
    }

    // ── 角色权限点分配 ─────────────────────────────────────────

    /** 查某角色的权限点。 */
    public Map<String, Object> rolePermissions(CurrentUser cu, long roleId) {
        long entId = requireEnterpriseContext();
        requireAnyPerm(cu, "role.read", "role.manage", "org.member.read");
        if (orgWriteMapper.countRoleInEnterprise(roleId, entId) == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不属于本企业：" + roleId);
        }
        List<String> codes = orgWriteMapper.selectRolePermCodes(roleId);
        return Map.of("roleId", roleId, "total", codes.size(), "codes", codes);
    }

    /**
     * 整体覆盖角色的权限点。
     *
     * <p><b>硬约束</b>：企业角色**不能**被授予平台级权限点（发布版本 / 改系统配置 / 密钥管理），
     * 否则任一企业的管理员都能影响全平台。
     */
    @Transactional
    public void setRolePermissions(CurrentUser cu, long roleId, List<String> codes) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "role.manage");
        if (orgWriteMapper.countRoleInEnterprise(roleId, entId) == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不属于本企业：" + roleId);
        }
        List<String> want = codes == null ? List.of() : codes.stream()
                .filter(c -> c != null && !c.isBlank()).distinct().toList();

        if (!want.isEmpty()) {
            // 平台级权限点：任何企业角色都不能有（否则企业管理员能发全平台版本等）
            List<String> bad = orgWriteMapper.selectPlatformOnlyAmong(want);
            if (!bad.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "企业角色不能授予平台级权限点：" + String.join(",", bad));
            }

            // 企业管理员：只能把「员工应得的 15 项」下放给员工。
            // 组织/角色/审计/大盘这 11 项属管理员职责，不能通过角色给员工。
            //
            // 例外：角色**已持有**的权限点允许原样保留。
            // 原因：内置模板（auditor 有 admin.audit.read、finance 有 admin.billing.*）
            // 是系统初始化的，若不允许"保留"，管理员只改一个文件权限就会被整单拒绝。
            // 该例外不放宽新增：不在集合内且角色原本没有的，一律拒绝。
            if (cu.isEnterpriseAdmin() && !cu.isAdmin()) {
                List<String> current = orgWriteMapper.selectRolePermCodes(roleId);
                List<String> notAssignable = want.stream()
                        .filter(c -> !EnterpriseAdminPermissions.canAssignToEmployee(c))
                        .filter(c -> !current.contains(c))
                        .toList();
                if (!notAssignable.isEmpty()) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "企业管理员只能给员工分配文件/回收站/传输/配额类权限，以下不能下放："
                                    + String.join(",", notAssignable));
                }
            }
        }
        orgWriteMapper.clearRolePerms(roleId);
        if (!want.isEmpty()) {
            orgWriteMapper.addRolePerms(roleId, want);
        }
    }

    /** 部门名非空且不超长。 */
    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称不能为空");
        }
        String n = name.trim();
        if (n.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称不能超过 64 字");
        }
        return n;
    }
    // ── 角色 CRUD ─────────────────────────────────────────────

    /**
     * 新建自定义角色。
     *
     * <p>code 是权限绑定的依据，**建后不可改**（改名只改 name）。
     * code 只允许小写字母/数字/下划线，避免前端拼 key 时出问题。
     */
    @Transactional
    public Map<String, Object> createRole(CurrentUser cu, String code, String name) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "role.manage");

        String c = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.isEmpty() || !c.matches("^[a-z0-9_]{1,48}$")) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "角色 code 只能是小写字母/数字/下划线，长度 1~48：" + code);
        }
        if ("sys_admin".equals(c)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sys_admin 是平台保留角色，不能创建");
        }
        String n = requireName2(name, "角色名称", 64);
        if (orgWriteMapper.countRoleCode(entId, c) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色 code 已存在：" + c);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entId", entId);
        row.put("code", c);
        row.put("name", n);
        orgWriteMapper.insertRole(row);
        Object id = row.get("id");
        return Map.of("id", id == null ? 0L : id, "code", c, "name", n, "builtin", false);
    }

    /** 改角色名（code 不变，权限绑定不受影响）。 */
    @Transactional
    public void renameRole(CurrentUser cu, long roleId, String name) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "role.manage");
        Boolean builtin = orgWriteMapper.selectRoleBuiltin(roleId, entId);
        if (builtin == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不属于本企业：" + roleId);
        }
        String n = requireName2(name, "角色名称", 64);
        orgWriteMapper.renameRole(roleId, entId, n);
    }

    /**
     * 删除自定义角色。
     *
     * <p>两道闸：内置角色不可删；仍绑定成员不可删（要求先解绑，避免成员突然失去全部权限）。
     */
    @Transactional
    public void deleteRole(CurrentUser cu, long roleId) {
        long entId = requireEnterpriseContext();
        requirePerm(cu, "role.manage");
        Boolean builtin = orgWriteMapper.selectRoleBuiltin(roleId, entId);
        if (builtin == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色不属于本企业：" + roleId);
        }
        if (Boolean.TRUE.equals(builtin)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置角色不可删除，只能改权限点");
        }
        int members = orgWriteMapper.countRoleMembers(roleId);
        if (members > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "还有 " + members + " 名成员绑定了该角色，请先解绑");
        }
        orgWriteMapper.deleteRole(roleId, entId);
    }

    /** 通用名称校验。 */
    private static String requireName2(String value, String label, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String v = value.trim();
        if (v.length() > maxLen) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能超过 " + maxLen + " 字");
        }
        return v;
    }
    /**
     * 仅企业管理员触发成员同步（平台管理员不需要：它看的是企业目录，不是本地成员）。
     *
     * <p>失败**不阻断**列表：门户抖动时至少还能看到本地已有成员，只是新员工暂时缺席。
     */
    private void syncMembersIfEnterpriseAdmin(Long entId) {
        if (entId == null || entId <= 0L) {
            return;
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null
                || !(auth.getPrincipal() instanceof com.company.cloud.auth.security.CurrentUser cu)) {
            return;
        }
        if (!cu.isEnterpriseAdmin() || cu.isAdmin()) {
            return;
        }
        var port = memberSyncPort == null ? null : memberSyncPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.syncMembers(entId);
        } catch (Exception e) {
            log.warn("[members] 门户成员同步失败，本次仅展示本地已有成员 entId={} err={}",
                    entId, e.toString());
        }
    }
    /**
     * {@code /me/context.roles} 内部用：不校验权限。
     *
     * <p>平台管理员没有"本企业"，返回空列表即可 —— 它的身份由
     * {@code isPlatformAdmin} 表达，不靠 RBAC 角色。
     */
    private List<Map<String, Object>> rolesOfEnterpriseOrAll(
            com.company.cloud.auth.security.CurrentUser cu) {
        Long entId = tenantIdForRead();
        if (entId == null || entId == 0L) {
            return List.of();
        }
        List<Map<String, Object>> rows = orgMapper.selectRoles(entId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            out.add(m);
        }
        return out;
    }
}