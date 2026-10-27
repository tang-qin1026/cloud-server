package com.company.cloud.auth.rbac.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RBAC 读取侧查询（MyBatis-Plus）。
 *
 * <p><b>为什么用 MyBatis 而不是 JPA</b>：
 * <ol>
 *   <li>{@code cloud-auth} 的 {@code ddl-auto=validate} 会用 JPA 实体校验表结构，
 *       实体与 DDL 任何偏差都**启动失败**；MyBatis 不参与该校验</li>
 *   <li>{@code role_permission} / {@code user_role} 是复合主键，JPA 需 {@code @IdClass}，啰嗦易错</li>
 * </ol>
 *
 * <p>⚠️ 包名必须落在 {@code **.mapper} 下 —— 启动类只扫 {@code @MapperScan("com.company.cloud.**.mapper")}。
 * 放错包会报 {@code No qualifying bean of type RbacQueryMapper}。
 */
@Mapper
public interface RbacQueryMapper {

    /** 该企业已建的角色数（0 = 需要惰性初始化 4 个模板）。 */
    @Select("SELECT count(*) FROM role WHERE enterprise_id = #{enterpriseId}")
    int countRoles(@Param("enterpriseId") long enterpriseId);

    /**
     * 建一个角色（幂等）：靠 {@code uk_role_code (enterprise_id, code)} 防并发生成两份。
     */
    @Select("""
            INSERT INTO role (enterprise_id, code, name, builtin)
            VALUES (#{enterpriseId}, #{code}, #{name}, TRUE)
            ON CONFLICT (enterprise_id, code) DO NOTHING
            RETURNING id
            """)
    Long insertRoleIfAbsent(@Param("enterpriseId") long enterpriseId,
                            @Param("code") String code,
                            @Param("name") String name);

    /** 给角色批量灌权限点（幂等）。 */
    @Select("""
            INSERT INTO role_permission (role_id, permission_code)
            SELECT r.id, c
            FROM role r, unnest(#{codes}::text[]) AS c
            WHERE r.enterprise_id = #{enterpriseId} AND r.code = #{roleCode}
            ON CONFLICT (role_id, permission_code) DO NOTHING
            RETURNING permission_code
            """)
    List<String> grantToRole(@Param("enterpriseId") long enterpriseId,
                             @Param("roleCode") String roleCode,
                             @Param("codes") String[] codes);

    /**
     * 用户的功能权限点（**多角色并集**，只含启用角色）。
     *
     * <p>⚠️ 只算功能权限。数据范围恒取 {@code users.data_scope}，**不来自角色**。
     */
    @Select("""
            SELECT DISTINCT rp.permission_code
            FROM user_role ur
            JOIN role r            ON r.id = ur.role_id AND r.status = 'active'
            JOIN role_permission rp ON rp.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermCodes(@Param("userId") long userId);

    /** 全部权限点 code（企业管理员 / 平台管理员 → 全量）。 */
    @Select("SELECT code FROM permission ORDER BY sort_no, code")
    List<String> selectAllPermCodes();
}
