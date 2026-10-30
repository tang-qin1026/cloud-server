package com.company.cloud.billing.dto;

import java.time.OffsetDateTime;

/**
 * 配额视图（docs/31 §8：企业管理员/成员需要知道"我的空间由哪几段组成"）。
 *
 * <p>拆三段，避免"我到底有多少空间"的困惑：
 * <ul>
 *   <li>{@code personalBytes}：个人免费额度（默认 1GB）</li>
 *   <li>{@code extraBytes}：个人购买的增量额度（带到期，个人侧原有语义）</li>
 *   <li>{@code enterpriseBytes}：管理员分给该成员的企业额度（新）</li>
 * </ul>
 *
 * <p>总额度 = personalBytes + extraBytes + enterpriseBytes。
 * {@code uploadBlocked} = usedBytes &gt; 总额度（推导值，不落库）。
 *
 * <p>旧字段 {@code freeBytes} 保留 = personalBytes，避免已对接的前端立刻挂掉。
 */
public record QuotaView(
        long freeBytes,
        long extraBytes,
        OffsetDateTime extraExpireAt,
        long usedBytes,
        boolean uploadBlocked,
        /** 个人免费额度（与 freeBytes 同值，语义更清晰的新名字）。 */
        long personalBytes,
        /** 管理员分给该成员的企业额度。 */
        long enterpriseBytes
) {
    /** 兼容旧构造器（调用方不用一次性全改）。 */
    public QuotaView(long freeBytes, long extraBytes, OffsetDateTime extraExpireAt,
                     long usedBytes, boolean uploadBlocked) {
        this(freeBytes, extraBytes, extraExpireAt, usedBytes, uploadBlocked, freeBytes, 0L);
    }

    /** 总可用额度。 */
    public long totalBytes() {
        return personalBytes + extraBytes + enterpriseBytes;
    }
}