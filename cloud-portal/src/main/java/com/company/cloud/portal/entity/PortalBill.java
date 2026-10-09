package com.company.cloud.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 门户账单（b_bill）。**先落库（status=0 待扣）再调门户扣费**。
 *
 * <p>相对契约 §4.2 的表结构，本实现新增两列（已在迁移注释中说明原因）：
 * <ul>
 *   <li>{@code portal_user_id} —— 契约 §6 的对账上报 payload 必填，必须在本表留存</li>
 *   <li>{@code biz_date} / {@code fail_code} / {@code retry_count} —— 对账按日聚合与失败可观测</li>
 * </ul>
 */
@Data
@TableName("b_bill")
public class PortalBill {

    /** 本侧账单号：{product_code}-{yyyyMMdd}-{6 位序列}。 */
    @TableId(type = IdType.INPUT)
    private String billNo;

    private Long localUserId;

    private String portalUserId;

    private String productCode;

    /** 金额（分）。 */
    private Long amountCents;

    /** 1 续费 / 2 按量 / 3 退款。 */
    private Integer type;

    /** 退款单指向的原单。 */
    private String relatedBillNo;

    /** 0 待扣 / 1 已入账 / 2 已退款。 */
    private Integer status;

    /** 最近一次失败的响应码。 */
    private Integer failCode;

    private Integer retryCount;

    private LocalDate bizDate;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
