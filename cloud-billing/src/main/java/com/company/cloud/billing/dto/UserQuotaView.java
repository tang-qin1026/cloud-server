package com.company.cloud.billing.dto;

import java.time.OffsetDateTime;

/**
 * users 表跨模块只读视图（billing/quota 查询与上传判定用）。
 */
public record UserQuotaView(
        Long id,
        String username,
        Long freeBytes,
        Long usedBytes,
        Long extraBytes,
        OffsetDateTime extraExpireAt,
        Long enterpriseAllocatedBytes
) {
    /** 总可用额度 = 免费 + 个人增量 + **企业分配**（docs/31 §6 闸 1）。 */
    public long totalAvailable() {
        return (freeBytes == null ? 0L : freeBytes)
                + (extraBytes == null ? 0L : extraBytes)
                + (enterpriseAllocatedBytes == null ? 0L : enterpriseAllocatedBytes);
    }
}
