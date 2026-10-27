package com.company.cloud.auth.org.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * M3 组织与权限的只读查询（MyBatis-Plus）。
 *
 * <p>⚠️ 包名必须在 {@code **.mapper} 下——启动类只扫 {@code @MapperScan("com.company.cloud.**.mapper")}。
 *
 * <p><b>租户边界由三处共同保证</b>：
 * <ol>
 *   <li>这里所有查询都显式带 {@code enterprise_id = #{entId}}（业务代码传入，不依赖拦截器）</li>
 *   <li>MyBatis 租户拦截器会给 {@code dept}/{@code role}/{@code users} 再补一次条件（幂等）</li>
 *   <li>平台管理员走 bypass，{@code entId} 传 null 时表示跨企业</li>
 * </ol>
 */
@Mapper
public interface OrgQueryMapper {

    // ── 权限点字典（全局，40 个）────────────────────────────────

    /** 全部权限点，按分组与排序号返回。 */
    @Select("""
            SELECT code, name, group_code, sort_no, high_risk
            FROM permission
            ORDER BY sort_no, code
            """)
    List<Map<String, Object>> selectAllPermissions();

    // ── 角色（按企业隔离）────────────────────────────────────────

    /**
     * 某企业的角色 + 每个角色的权限点数量。
     *
     * @param entId 企业 id；null 表示跨企业（仅平台管理员）
     */
    @Select("""
            <script>
            SELECT r.id, r.code, r.name, r.builtin, r.status, r.enterprise_id,
                   COUNT(rp.permission_code) AS perm_count
            FROM role r
            LEFT JOIN role_permission rp ON rp.role_id = r.id
            <where>
              <if test="entId != null">r.enterprise_id = #{entId}</if>
            </where>
            GROUP BY r.id, r.code, r.name, r.builtin, r.status, r.enterprise_id
            ORDER BY r.builtin DESC, r.id
            </script>
            """)
    List<Map<String, Object>> selectRoles(@Param("entId") Long entId);

    /** 某用户已绑定的角色 id 集合。 */
    @Select("SELECT role_id FROM user_role WHERE user_id = #{userId}")
    List<Long> selectUserRoleIds(@Param("userId") long userId);

    // ── 部门（按企业隔离，物化路径）──────────────────────────────

    /**
     * 部门列表（扁平，含 path，前端自行建树）。
     */
    @Select("""
            <script>
            SELECT d.id, d.enterprise_id, d.parent_id, d.name, d.path, d.status, d.sort_no
            FROM dept d
            <where>
              <if test="entId != null">d.enterprise_id = #{entId}</if>
              <if test="status != null and status != ''">AND d.status = #{status}</if>
            </where>
            ORDER BY d.path, d.sort_no, d.id
            </script>
            """)
    List<Map<String, Object>> selectDepts(@Param("entId") Long entId, @Param("status") String status);

    /** 单个部门（校验归属用）。 */
    @Select("SELECT id, enterprise_id, parent_id, name, path FROM dept WHERE id = #{id}")
    Map<String, Object> selectDeptById(@Param("id") long id);

    // ── 成员（users + 部门名 + 角色）─────────────────────────────

    /**
     * 成员列表（分页）。**企业域按 enterprise_id 过滤；个人域（0）同理。**
     *
     * <p>{@code entId} 为 null 表示跨企业（平台管理员）。
     */
    @Select("""
            <script>
            SELECT u.id, u.username, u.nickname, u.role, u.enterprise_role, u.enterprise_id,
                   u.dept_id, u.data_scope, u.status, u.last_login_at, u.created_at,
                   d.name AS dept_name
            FROM users u
            LEFT JOIN dept d ON d.id = u.dept_id
            <where>
              <if test="entId != null">u.enterprise_id = #{entId}</if>
              <if test="keyword != null and keyword != ''">
                AND (u.username ILIKE CONCAT('%', #{keyword}, '%')
                     OR u.nickname ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">AND u.status = #{status}</if>
              <if test="deptId != null">AND u.dept_id = #{deptId}</if>
            </where>
            ORDER BY u.enterprise_role DESC, u.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectMembers(@Param("entId") Long entId,
                                            @Param("keyword") String keyword,
                                            @Param("status") String status,
                                            @Param("deptId") Long deptId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    /** 成员总数（与 {@link #selectMembers} 条件一致）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM users u
            <where>
              <if test="entId != null">u.enterprise_id = #{entId}</if>
              <if test="keyword != null and keyword != ''">
                AND (u.username ILIKE CONCAT('%', #{keyword}, '%')
                     OR u.nickname ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">AND u.status = #{status}</if>
              <if test="deptId != null">AND u.dept_id = #{deptId}</if>
            </where>
            </script>
            """)
    long countMembers(@Param("entId") Long entId,
                      @Param("keyword") String keyword,
                      @Param("status") String status,
                      @Param("deptId") Long deptId);

    /** 按 id 取用户的企业归属（跨租户断言用）。 */
    @Select("SELECT enterprise_id FROM users WHERE id = #{id}")
    Long selectUserEnterpriseId(@Param("id") long id);

    // ── 企业 ───────────────────────────────────────────────────

    @Select("SELECT id, code, name, status, owner_user_id, expire_at, reject_reason FROM enterprise WHERE id = #{id}")
    Map<String, Object> selectEnterpriseById(@Param("id") long id);

    @Select("""
            <script>
            SELECT id, code, name, status, owner_user_id, expire_at, created_at
            FROM enterprise
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectEnterprises(@Param("status") String status,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM enterprise
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            </script>
            """)
    long countEnterprises(@Param("status") String status);
    // ── 数据范围：可见的"他人"列表 ──────────────────────────────
    //
    // 这是三档数据范围（self / dept_subtree / all）在 SQL 层的**唯一权威实现**，
    // 后续 files / audit_logs / billing 等业务查询复用同一套语义。
    //
    //   self         → 调用方直接返回空（服务层短路，不必查库）
    //   dept_subtree → 本部门 + 所有下级部门（dept.path LIKE 前缀），天然排除兄弟与上级
    //   all          → 本企业全部成员
    //
    // 三档都**仍受 enterprise_id 约束**：看得到全公司，看不到别家公司。
    @Select("""
            <script>
            SELECT u.id, u.username, u.nickname, u.dept_id, d.name AS dept_name
            FROM users u
            LEFT JOIN dept d ON d.id = u.dept_id
            WHERE u.enterprise_id = #{entId}
              AND u.id &lt;&gt; #{meId}
              <if test="scope == 'dept_subtree'">
                <choose>
                  <when test="myPath != null and myPath != ''">
                    AND u.dept_id IN (
                      SELECT dd.id FROM dept dd
                      WHERE dd.enterprise_id = #{entId}
                        AND dd.path LIKE CONCAT(#{myPath}, '%')
                    )
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </if>
            ORDER BY u.dept_id NULLS LAST, u.id
            LIMIT 500
            </script>
            """)
    List<Map<String, Object>> selectVisibleOwners(@Param("entId") long entId,
                                                  @Param("meId") long meId,
                                                  @Param("scope") String scope,
                                                  @Param("myPath") String myPath);

    /** 取某用户的部门 id 与物化路径（算 dept_subtree 用）。 */
    @Select("""
            SELECT d.id AS dept_id, d.path
            FROM users u
            LEFT JOIN dept d ON d.id = u.dept_id
            WHERE u.id = #{userId}
            """)
    Map<String, Object> selectUserDeptPath(@Param("userId") long userId);
}