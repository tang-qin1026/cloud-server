package com.company.cloud.portal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理端手工出账请求（续费 / 补账）。
 *
 * <p>之所以提供这个入口：b_bill 的「先落库再扣费」机制需要一个可观测、可验证的触发点。
 * 契约里包周期订单由门户在下单时扣费，产品侧只负责按量后付出账与退款，
 * 因此本接口定位为**运维/联调工具**，不是业务主链路。
 */
public record AdminChargeRequest(
        @NotBlank(message = "portalUserId 不能为空") String portalUserId,
        @NotNull(message = "amountCents 不能为空") @Min(value = 1, message = "金额必须大于 0 分") Long amountCents,
        /** 1 续费 / 2 按量。 */
        @NotNull(message = "type 不能为空") Integer type,
        /** 关联业务单号（可空）。 */
        String relatedBillNo) {
}
