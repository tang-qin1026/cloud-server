package com.company.cloud.files.stats.dto;

/**
 * 用量 Top 用户项（R-C09，契约 GET /api/admin/stats/top-users）。
 *
 * @param userId     用户 ID
 * @param username   用户名
 * @param usedBytes  已用字节（含回收站占用，与配额同口径）
 * @param quotaBytes 配额字节
 * @param nickname   展示名（门户用户的 username 是 portal_<uuid>，必须用 nickname 显示）
 */
public record TopUser(
        long userId,
        String username,
        long usedBytes,
        long quotaBytes,
        String nickname
) {
}
