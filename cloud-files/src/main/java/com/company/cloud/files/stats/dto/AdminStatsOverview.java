package com.company.cloud.files.stats.dto;

/**
 * 管理端大盘总览（R-C09，契约 GET /api/admin/stats/overview）。
 *
 * @param totalQuotaBytes 配额总量（quota_bytes + extra_bytes + enterprise_allocated_bytes 之和：
 *                        免费额度 + 计费增量 + 管理员分配的企业额度）
 * @param usedBytes       全平台已用总量（所有用户 used_bytes 之和，含回收站占用，与配额同口径）
 * @param remainingBytes  剩余可分配量（total - used，下限 0）
 * @param userCount       注册用户总数
 */
public record AdminStatsOverview(
        long totalQuotaBytes,
        long usedBytes,
        long remainingBytes,
        long userCount
) {
}
