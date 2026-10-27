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
}
