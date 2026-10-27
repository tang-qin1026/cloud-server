package com.company.cloud.auth.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户（对标任务书 req.user = { id, username, role }）。
 *
 * <p>在认证过滤器 {@code JwtAuthFilter} / {@code PortalJwtAuthFilter} 中注入到 SecurityContext。
 *
 * <p><b>V2025（M2 租户骨架）新增三个字段</b>，来源是门户 JWT：
 * <ul>
 *   <li>{@link #userType} —— {@code ut} claim：0 个人 / 1 企业员工</li>
 *   <li>{@link #enterpriseId} —— {@code ent} claim：企业 id；0 = 个人域</li>
 *   <li>{@link #enterpriseRole} —— {@code er} claim：1 = 企业管理员（缺失按 0）</li>
 * </ul>
 *
 * <p>⚠️ 保留了 **3 参构造器** {@code (id, username, role)} 以兼容既有调用点
 * （{@code JwtAuthFilter} / {@code PortalShadowUserService}）；
 * 新代码请用 6 参构造器显式带上租户信息。
 */
@Data
@AllArgsConstructor
public class CurrentUser {

    private Long id;
    private String username;
    private String role;

    /** user_type：0 个人 / 1 企业员工（门户 JWT 的 {@code ut}）。 */
    private Integer userType;

    /** 所属企业 id；0 = 个人域（门户 JWT 的 {@code ent}）。 */
    private Long enterpriseId;

    /** 1 = 企业管理员（门户 JWT 的 {@code er}）。 */
    private Integer enterpriseRole;

    /** 兼容旧调用点：只带身份，不带租户信息（视为个人域、非企业管理员）。 */
    public CurrentUser(Long id, String username, String role) {
        this(id, username, role, 0, 0L, 0);
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    /** 是否企业员工（{@code ut = 1}）。 */
    public boolean isEnterpriseUser() {
        return userType != null && userType == 1;
    }

    /** 是否企业管理员（{@code er = 1}）。 */
    public boolean isEnterpriseAdmin() {
        return enterpriseRole != null && enterpriseRole == 1;
    }

    /** 是否个人域（未加入任何企业）。 */
    public boolean isPersonal() {
        return enterpriseId == null || enterpriseId == 0L;
    }
}
