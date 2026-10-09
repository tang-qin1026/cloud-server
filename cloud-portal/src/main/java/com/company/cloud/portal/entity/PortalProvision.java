package com.company.cloud.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 门户开通记录（portal_provision）。
 *
 * <p>状态机：{@code pending → granting → active → grace → expired}，异常终态 {@code failed}。
 * <ul>
 *   <li>{@code pending}  ：已落库、尚未开始建实例（幂等键 order_item_id 已占位）</li>
 *   <li>{@code granting} ：已抢占，正在授予配额（同一 order_item_id 只有抢占成功者会执行）</li>
 *   <li>{@code active}   ：开通完成</li>
 *   <li>{@code grace}    ：已到期，处于宽限期（建议 7 天）</li>
 *   <li>{@code expired}  ：宽限期结束，配额已回收（停机）</li>
 * </ul>
 */
@Data
@TableName("portal_provision")
public class PortalProvision {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 门户订单项 id；幂等键，唯一索引 uk_portal_provision_order_item。 */
    private Long orderItemId;

    private String portalUserId;

    private Long localUserId;

    private String skuId;

    private Integer quantity;

    private Integer periodDays;

    /** 实际授予的配额（字节）。 */
    private Long grantedBytes;

    /** 返回给门户的实例号，形如 st-000001。 */
    private String instanceId;

    private String status;

    private OffsetDateTime expireAt;

    private OffsetDateTime graceEndAt;

    private Boolean quotaReclaimed;

    /** 按量计费最近一次出账时刻。 */
    private OffsetDateTime lastBilledAt;

    /** 门户透传 extra 的 JSON 原文。 */
    private String extra;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
