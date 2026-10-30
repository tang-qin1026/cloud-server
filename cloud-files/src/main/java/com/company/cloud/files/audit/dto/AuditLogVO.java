package com.company.cloud.files.audit.dto;

import com.company.cloud.files.audit.entity.AuditLog;

import java.time.OffsetDateTime;

/**
 * 审计日志视图（R-C09 查询返回前端）。detail 以 JSON 文本原样返回，由前端解析展示。
 *
 * <p><b>为什么有 4 个"名字"字段</b>：库里只存 id（user_id / target），
 * 直接返回会让界面显示成一串数字。所以查询侧补两层：
 * <ul>
 *   <li>{@code username} —— 登录名（门户用户是 portal_&lt;uuid&gt;，不能直接展示）</li>
 *   <li>{@code actorName} —— **展示名**（nickname 优先，回退 username）</li>
 *   <li>{@code target} —— 原始目标（id 或用户名，保留兼容）</li>
 *   <li>{@code targetName} —— **目标展示名**（文件名 / 用户昵称 / 角色名等）</li>
 * </ul>
 *
 * <p>前端一律用 {@code actorName} / {@code targetName} 展示；
 * 拿不到名字时它们会回退成原始值，不会是 null。
 */
public record AuditLogVO(
        Long id,
        Long userId,
        String username,
        String actorName,
        String action,
        String target,
        String targetName,
        String ip,
        String detail,
        String status,
        OffsetDateTime createdAt
) {
    public static AuditLogVO from(AuditLog log) {
        return from(log, null, null, null);
    }

    public static AuditLogVO from(AuditLog log, String username) {
        return from(log, username, null, null);
    }

    public static AuditLogVO from(AuditLog log, String username, String actorName, String targetName) {
        String actor = actorName != null && !actorName.isBlank() ? actorName : username;
        String tgt = targetName != null && !targetName.isBlank() ? targetName : log.getTarget();
        return new AuditLogVO(
                log.getId(),
                log.getUserId(),
                username,
                actor,
                log.getAction(),
                log.getTarget(),
                tgt,
                log.getIp(),
                log.getDetail(),
                log.getStatus(),
                log.getCreatedAt()
        );
    }
}