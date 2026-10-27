package com.company.cloud.auth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * users 表实体（与 V1__create_users.sql 对齐，ddl-auto=validate 校验）
 *
 * <p>P2 字段补充（V1002）：nickname / email / last_login_at，对应用户资料展示。
 *
 * <p><b>关于 {@code @Builder.Default}（勿删）</b>：
 * 本类所有 {@code NOT NULL} 且有初值的字段都必须加 {@code @Builder.Default}。
 * Lombok 的 {@code @Builder} 会生成一个"全参数"构造路径，<b>绕过字段初始值</b>——
 * 于是凡是用 {@code User.builder()...build()} 创建对象、又没有显式设置该字段的地方，
 * 字段就是 {@code null}，插入时违反非空约束，接口报 50000「系统繁忙」。
 *
 * <p>该缺陷真实发生过：{@code AdminUserService} 建普通用户时漏设 {@code extraBytes}，
 * 导致管理员无法创建用户（数据库列虽有 {@code DEFAULT 0}，但 Hibernate 的 INSERT
 * 显式带上了该列并写 NULL，默认值不生效）。
 * 加上 {@code @Builder.Default} 后 builder 会尊重字段初值，此类问题从根上消除。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * 密码哈希。
     *
     * <p>⚠️ {@code @JsonIgnore} **必须保留**：本实体被 {@code /api/admin/users} 直接当作
     * 响应体返回，少了它就会把哈希泄露给前端。
     */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Builder.Default
    @Column(nullable = false)
    private String role = "user";

    @Builder.Default
    @Column(name = "quota_bytes", nullable = false)
    private Long quotaBytes = 21474836480L; // 默认 20GB

    @Builder.Default
    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes = 0L;

    @Builder.Default
    @Column(nullable = false)
    private String status = "active";

    @Builder.Default
    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword = true;

    /** 显示昵称（可选，V1002） */
    private String nickname;

    /** 邮箱（可选，唯一，V1002） */
    private String email;

    /** 最近登录时间（登录成功时回填，V1002） */
    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /** 有效增量额度之和（冗余，由 D 组 billing 在审批通过/到期收回两个写点维护，V2008） */
    @Builder.Default
    @Column(name = "extra_bytes", nullable = false)
    private Long extraBytes = 0L;

    /** 最近一笔增量到期时刻（冗余展示，可空，V2008） */
    @Column(name = "extra_expire_at")
    private OffsetDateTime extraExpireAt;

    // ── 多租户与数据权限（V2025，M2 租户骨架）────────────────────────

    /**
     * 所属企业；{@code 0} = 个人域。
     *
     * <p>来源是门户 JWT 的 {@code ent} claim（企业归属的权威在门户），
     * 产品侧只做本地投影，**不存归属真值**。
     */
    @Builder.Default
    @Column(name = "enterprise_id", nullable = false)
    private Long enterpriseId = 0L;

    /** 所属部门；{@code null} = 未分配部门（挂在企业根下）。 */
    @Column(name = "dept_id")
    private Long deptId;

    /**
     * 数据权限档位，三档：{@code self} / {@code dept_subtree} / {@code all}。
     *
     * <p>⚠️ 挂在**用户**上，与角色无关（角色只决定功能权限）。
     */
    /**
     * 数据权限档位（DB 存小写，与 CHECK 约束一致）：{@code self} / {@code dept_subtree} / {@code all}。
     *
     * <p>对外 JSON 由 {@link #getDataScope()} 转成设计方案里的档位名，见下。
     */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Column(name = "data_scope", nullable = false)
    private String dataScope = "self";

    /**
     * 企业管理员标记：{@code 1} = 企业管理员（创建者/主管理员）。
     *
     * <p><b>判"企业管理员"用本字段，不要用 {@code enterprise.owner_user_id}</b>——
     * 后者只表示创建者/主管理员、转让时才改；用本字段将来可扩到多管理员。
     */
    @Builder.Default
    @Column(name = "enterprise_role", nullable = false)
    private Short enterpriseRole = 0;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** 仅服务端使用，不参与 JSON 序列化（避免暴露内部判断语义）。 */
    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(this.role);
    }

    /** 仅服务端使用，不参与 JSON 序列化（避免暴露内部判断语义）。 */
    @JsonIgnore
    public boolean isDisabled() {
        return "disabled".equals(this.status);
    }

    /** 是否企业管理员（{@code enterprise_role = 1}）。 */
    /** 仅服务端使用，不参与 JSON 序列化（避免暴露内部判断语义）。 */
    @JsonIgnore
    public boolean isEnterpriseAdmin() {
        return enterpriseRole != null && enterpriseRole == 1;
    }

    /** 是否个人域账号（未加入任何企业）。 */
    /** 仅服务端使用，不参与 JSON 序列化（避免暴露内部判断语义）。 */
    @JsonIgnore
    public boolean isPersonal() {
        return enterpriseId == null || enterpriseId == 0L;
    }

    /**
     * 数据权限档位，**对外返回设计方案的档位名**（小写入库、大写出参）。
     *
     * <p>映射：{@code self→SELF}、{@code dept_subtree→DEPT_AND_BELOW}、{@code all→ALL}。
     * 为什么不在库里直接存大写：CHECK 约束已按小写建立（V2025），
     * 改库需要额外迁移；而前端契约只关心出参，因此在校验层保持小写、在序列化层转换。
     */
    @JsonProperty("dataScope")
    public String getDataScope() {
        if (dataScope == null) {
            return null;
        }
        return switch (dataScope) {
            case "self" -> "SELF";
            case "dept_subtree" -> "DEPT_AND_BELOW";
            case "all" -> "ALL";
            default -> dataScope.toUpperCase(java.util.Locale.ROOT);
        };
    }

    /** 内部使用的原始值（小写，与数据库 CHECK 一致）。 */
    @JsonIgnore
    public String getDataScopeRaw() {
        return dataScope;
    }
}
