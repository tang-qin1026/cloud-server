package com.company.cloud.auth.entity;

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

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public boolean isAdmin() {
        return "admin".equals(this.role);
    }

    public boolean isDisabled() {
        return "disabled".equals(this.status);
    }
}
