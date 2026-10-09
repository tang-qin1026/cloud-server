package com.company.cloud.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理端退款请求（契约 §4.4）。{@code refund_bill_no} 由本侧生成，无需外部传入。
 */
@Schema(name = "AdminRefundRequest", description = "退款请求（注意：本请求体字段是 camelCase）")
public record AdminRefundRequest(

        @Schema(description = "原扣费账单号（`b_bill.bill_no`，形态 `storage-20261009-000002`）",
                example = "storage-20261009-000002", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "billNo 不能为空") String billNo,

        @Schema(description = "本次退款金额，单位**分**（必须 > 0，且累计退款不得超过原单金额）",
                example = "1000", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "amountCents 不能为空") @Min(value = 1, message = "退款金额必须大于 0 分") Long amountCents,

        @Schema(description = "退款原因（可空，缺省为「管理端发起退款」）", example = "开通失败")
        String reason) {
}
