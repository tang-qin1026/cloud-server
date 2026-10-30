package com.company.cloud.auth.org.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * M3 组织与权限的写操作（MyBatis-Plus）。
 *
 * <p><b>每条 SQL 都带 {@code enterprise_id} 条件</b>，即使调用方已经校验过归属——
 * 这是第三道防线（显式断言）在 SQL 层的延伸：即使上层漏判，也不会改到别家企业的数据。
 */
@Mapper
public interface OrgWriteMapper {

    // ── 成员配置（部门 + 数据范围）────────────────────────────────

    /**
     * 更新成员的部门与数据权限档位。
     *
     * <p>⚠️ 这里**不能**改 {@code enterprise_id}：企业归属由门户决定，产品侧只做投影。
     *
     * @return 影响行数；0 表示该用户不属于该企业（上层据此返回 404）
     */
    @Update("""
            UPDATE users
            SET dept_id = #{deptId}, data_scope = #{dataScope}, updated_at = now()
            WHERE id = #{userId} AND enterprise_id = #{entId}
            """)
    int updateMemberScope(@Param("userId") long userId,
                          @Param("entId") long entId,
                          @Param("deptId") Long deptId,
                          @Param("dataScope") String dataScope);

    /** 清空某用户的角色绑定（配合 {@link #addUserRoles} 实现"整体覆盖"）。 */
    @Delete("DELETE FROM user_role WHERE user_id = #{userId}")
    int clearUserRoles(@Param("userId") long userId);

    /** 批量绑定角色。 */
    @Insert("""
            <script>
            INSERT INTO user_role(user_id, role_id) VALUES
            <foreach collection="roleIds" item="rid" separator=",">(#{userId}, #{rid})</foreach>
            ON CONFLICT (user_id, role_id) DO NOTHING
            </script>
            """)
    int addUserRoles(@Param("userId") long userId, @Param("roleIds") List<Long> roleIds);

    /** 取某企业的合法角色 id 集合（校验传入的 roleIds 不能跨企业）。 */
    @Select("SELECT id FROM role WHERE enterprise_id = #{entId}")
    List<Long> selectRoleIdsOfEnterprise(@Param("entId") long entId);

    // ── 部门合法性 ─────────────────────────────────────────────

    /** 部门是否属于该企业（0 = 未分配部门，调用方自行处理）。 */
    @Select("SELECT COUNT(*) FROM dept WHERE id = #{deptId} AND enterprise_id = #{entId}")
    int countDeptInEnterprise(@Param("deptId") long deptId, @Param("entId") long entId);

    // ── 资源分配 ───────────────────────────────────────────────

    /** 企业资源池（含持有人）。 */
    @Select("""
            SELECT id, sku_id, quota_gb, holder_user_id, assigned_at
            FROM storage_resource
            WHERE enterprise_id = #{entId}
            ORDER BY id
            """)
    List<Map<String, Object>> selectResources(@Param("entId") long entId);

    /** 某成员当前持有的资源 id。 */
    @Select("""
            SELECT id FROM storage_resource
            WHERE enterprise_id = #{entId} AND holder_user_id = #{userId}
            """)
    List<Long> selectResourceIdsHeldBy(@Param("entId") long entId, @Param("userId") long userId);

    /** 把资源改成指定持有人（分配或收回）。 */
    @Update("""
            UPDATE storage_resource
            SET holder_user_id = #{holderId}, assigned_at = CASE WHEN #{holderId} = #{ownerId} THEN NULL ELSE now() END
            WHERE id = #{id} AND enterprise_id = #{entId}
            """)
    int updateHolder(@Param("id") long id,
                     @Param("entId") long entId,
                     @Param("holderId") long holderId,
                     @Param("ownerId") long ownerId);

    /** 资源 id 是否都属于该企业（防跨企业分配）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM storage_resource
            WHERE enterprise_id = #{entId} AND id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    int countResourcesInEnterprise(@Param("ids") List<Long> ids, @Param("entId") long entId);

    /** 企业主管理员（资源收回的目标：资源归人不归企业，收回回到主管理员名下）。 */
    @Select("SELECT owner_user_id FROM enterprise WHERE id = #{entId}")
    Long selectEnterpriseOwner(@Param("entId") long entId);
    // ── 部门 CRUD（物化路径 /id/）────────────────────────────────

    /** 取部门（含 path），用于算新路径与校验归属。 */
    @Select("""
            SELECT id, parent_id, name, path FROM dept
            WHERE id = #{id} AND enterprise_id = #{entId}
            """)
    Map<String, Object> selectDeptInEnterprise(@Param("id") long id, @Param("entId") long entId);

    /** 子部门数量。 */
    @Select("SELECT COUNT(*) FROM dept WHERE enterprise_id = #{entId} AND parent_id = #{parentId}")
    int countChildDepts(@Param("entId") long entId, @Param("parentId") long parentId);

    /** 挂在某部门下的成员数量（删除前检查）。 */
    @Select("SELECT COUNT(*) FROM users WHERE enterprise_id = #{entId} AND dept_id = #{deptId}")
    int countMembersInDept(@Param("entId") long entId, @Param("deptId") long deptId);

    /** 同层同名（启用态）数量，用于友好报错（DB 唯一索引是最终防线）。 */
    @Select("""
            SELECT COUNT(*) FROM dept
            WHERE enterprise_id = #{entId} AND parent_id = #{parentId}
              AND name = #{name} AND status = 'active' AND id <> #{excludeId}
            """)
    int countSiblingName(@Param("entId") long entId, @Param("parentId") long parentId,
                         @Param("name") String name, @Param("excludeId") long excludeId);

    /** 插入部门（先占位 path，拿到 id 后再回填）。 */
    @Insert("""
            INSERT INTO dept(enterprise_id, parent_id, name, path, sort_no)
            VALUES (#{entId}, #{parentId}, #{name}, #{tmpPath}, #{sortNo})
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDept(Map<String, Object> row);

    /** 回填自身 path（以及级联子孙）。 */
    @Update("UPDATE dept SET path = #{path}, updated_at = now() WHERE id = #{id} AND enterprise_id = #{entId}")
    int updateDeptPath(@Param("id") long id, @Param("entId") long entId, @Param("path") String path);

    /**
     * 级联改写子孙的 path：把 oldPrefix 换成 newPrefix。
     *
     * <p>移动部门时**必须**调用，否则子树的 path 还指向旧父级，
     * 数据范围 {@code dept_subtree} 用 LIKE 前缀匹配会直接漏掉整个子树。
     */
    @Update("""
            UPDATE dept
            SET path = #{newPrefix} || SUBSTRING(path FROM LENGTH(#{oldPrefix}) + 1), updated_at = now()
            WHERE enterprise_id = #{entId} AND path LIKE CONCAT(#{oldPrefix}, '%') AND id <> #{selfId}
            """)
    int rewriteDescendantPaths(@Param("entId") long entId, @Param("oldPrefix") String oldPrefix,
                               @Param("newPrefix") String newPrefix, @Param("selfId") long selfId);

    /**
     * 改名 / 改父 / 改排序。
     *
     * <p>⚠️ {@code parentId} 必须是包装类型 {@code Long}：顶层部门传 {@code null}，
     * 用基本类型会在调用处拆箱 NPE（真实踩过）。
     */
    @Update("""
            UPDATE dept SET name = #{name}, parent_id = #{parentId}, sort_no = #{sortNo}, updated_at = now()
            WHERE id = #{id} AND enterprise_id = #{entId}
            """)
    int updateDept(@Param("id") long id, @Param("entId") long entId,
                   @Param("name") String name, @Param("parentId") Long parentId,
                   @Param("sortNo") int sortNo);

    @Delete("DELETE FROM dept WHERE id = #{id} AND enterprise_id = #{entId}")
    int deleteDept(@Param("id") long id, @Param("entId") long entId);

    // ── 角色权限点分配 ─────────────────────────────────────────

    /** 角色是否属于该企业。 */
    @Select("SELECT COUNT(*) FROM role WHERE id = #{roleId} AND enterprise_id = #{entId}")
    int countRoleInEnterprise(@Param("roleId") long roleId, @Param("entId") long entId);

    @Select("SELECT permission_code FROM role_permission WHERE role_id = #{roleId}")
    List<String> selectRolePermCodes(@Param("roleId") long roleId);

    @Delete("DELETE FROM role_permission WHERE role_id = #{roleId}")
    int clearRolePerms(@Param("roleId") long roleId);

    @Insert("""
            <script>
            INSERT INTO role_permission(role_id, permission_code) VALUES
            <foreach collection="codes" item="c" separator=",">(#{roleId}, #{c})</foreach>
            ON CONFLICT DO NOTHING
            </script>
            """)
    int addRolePerms(@Param("roleId") long roleId, @Param("codes") List<String> codes);

    /**
     * 平台级权限点（不可授予企业角色）。
     *
     * <p>判定依据：{@code group_code = 'release_sys'}（发布与系统，共 5 项）。
     * 这是 V2025 种子数据里平台级权限点所在的分组。
     */
    @Select("""
            <script>
            SELECT code FROM permission WHERE group_code = 'release_sys' AND code IN
            <foreach collection="codes" item="c" open="(" separator="," close=")">#{c}</foreach>
            </script>
            """)
    List<String> selectPlatformOnlyAmong(@Param("codes") List<String> codes);
    // ── 角色 CRUD ──────────────────────────────────────────────

    /** 同企业下 code 是否已占用（DB 的 uk_role_code 是最终防线，这里给友好报错）。 */
    @Select("SELECT COUNT(*) FROM role WHERE enterprise_id = #{entId} AND code = #{code}")
    int countRoleCode(@Param("entId") long entId, @Param("code") String code);

    /** 插入自定义角色（builtin = FALSE）。 */
    @Insert("""
            INSERT INTO role(enterprise_id, code, name, builtin)
            VALUES (#{entId}, #{code}, #{name}, FALSE)
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRole(Map<String, Object> row);

    /** 改角色名（code 不可改：它是权限绑定的依据）。 */
    @Update("""
            UPDATE role SET name = #{name}, updated_at = now()
            WHERE id = #{id} AND enterprise_id = #{entId}
            """)
    int renameRole(@Param("id") long id, @Param("entId") long entId, @Param("name") String name);

    /** 是否内置角色（内置角色不可删除）。 */
    @Select("SELECT builtin FROM role WHERE id = #{id} AND enterprise_id = #{entId}")
    Boolean selectRoleBuiltin(@Param("id") long id, @Param("entId") long entId);

    /** 已绑定该角色的成员数（删除前检查）。 */
    @Select("SELECT COUNT(*) FROM user_role WHERE role_id = #{roleId}")
    int countRoleMembers(@Param("roleId") long roleId);

    /** 删除自定义角色（builtin=FALSE 作为最后一道闸）。 */
    @Delete("DELETE FROM role WHERE id = #{id} AND enterprise_id = #{entId} AND builtin = FALSE")
    int deleteRole(@Param("id") long id, @Param("entId") long entId);
    // ── 影子企业（门户企业的本地投影）──────────────────────────

    /**
     * 按门户的 enterprise_id 建/补本地企业行。
     *
     * <p><b>为什么必须有</b>：企业归属的权威在门户，我们只投影。但
     * {@code /admin/login} 判定企业管理员时要查本地 {@code enterprise.status}
     * （见 AuthService.isActiveEnterpriseAdmin）——本地没有这行就查不到状态，
     * fail-closed 直接拒绝。表现为「门户注册的企业管理员永远进不了管理端」。
     *
     * <p>用显式 id 插入（BIGSERIAL 允许），保证本地 id 与门户 {@code ent} 一致；
     * {@code ON CONFLICT} 时只补 owner（不覆盖已有值），name/code 留给后台同步修正。
     */
    @Insert("""
            INSERT INTO enterprise(id, code, name, status, owner_user_id)
            VALUES (#{id}, #{code}, #{name}, 'active', #{ownerUserId})
            ON CONFLICT (id) DO UPDATE
              SET owner_user_id = COALESCE(enterprise.owner_user_id, EXCLUDED.owner_user_id),
                  updated_at = now()
            """)
    int upsertPortalEnterprise(@Param("id") long id,
                               @Param("code") String code,
                               @Param("name") String name,
                               @Param("ownerUserId") Long ownerUserId);
    /** 用门户的真实企业名/编码覆盖影子企业的占位值（不动 owner / status）。 */
    @Update("UPDATE enterprise SET code = #{code}, name = #{name}, updated_at = now() WHERE id = #{id}")
    int updateEnterpriseProfile(@Param("id") long id,
                                @Param("code") String code,
                                @Param("name") String name);
}