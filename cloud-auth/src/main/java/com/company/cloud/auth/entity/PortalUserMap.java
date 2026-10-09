package com.company.cloud.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 门户用户 → 本地影子用户映射（表 portal_user_map，迁移 V2020）。
 *
 * <p>门户有独立用户体系，中心身份是门户 UUID（门户 JWT 的 sub）。
 * 首次访问业务接口时按本表「首达建档」：无记录则建本地 users 行 + 写本表（同一事务）。
 *
 * <p>并发首达由两个唯一约束兜底：
 * <ul>
 *   <li>{@code pk_portal_user_map} —— 同一门户用户只能有一条映射</li>
 *   <li>{@code uk_portal_user_map_local} —— 一个本地用户不能被两个门户用户占用</li>
 * </ul>
 * 插入冲突时回查一次即可（见 PortalShadowUserService）。
 */
@Entity
@Table(name = "portal_user_map")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalUserMap {

    /** 门户用户 UUID（门户 JWT 的 sub），主键。 */
    @Id
    @Column(name = "portal_user_id", length = 36, nullable = false)
    private String portalUserId;

    /** 本系统 users.id。 */
    @Column(name = "local_user_id", nullable = false, unique = true)
    private Long localUserId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
