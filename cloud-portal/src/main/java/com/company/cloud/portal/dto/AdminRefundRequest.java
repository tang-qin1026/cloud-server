package com.company.cloud.portal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理端退款请求（契约 §4.4）。{@code refund_bill_no} 由本侧生成，无需外部传入。
 */
public record AdminRefundRequest(
        @NotBlank(message = "billNo 不能为空") String billNo,
        @NotNull(message = "amountCents 不能为空") @Min(value = 1, message = "退款金额必须大于 0 分") Long amountCents,
        String reason) {
}
