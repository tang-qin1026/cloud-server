package com.company.cloud.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理端手工出账请求（续费 / 补账）。
 *
 * <p>之所以提供这个入口：b_bill 的「先落库再扣费」机制需要一个可观测、可验证的触发点。
 * 契约里包周期订单由门户在下单时扣费，产品侧只负责按量后付出账与退款，
 * 因此本接口定位为**运维/联调工具**，不是业务主链路。
 *
 * <p><b>注意字段命名</b>：本请求体用 camelCase（`portalUserId` 等），
 * 而 `/internal/**` 的两个内网接口按契约用 snake_case（`portal_user_id` 等）。这是刻意的：
 * 内网接口的字段名由门户契约强制，运维接口无契约约束故沿用 Java 风格。
 */
@Schema(name = "AdminChargeRequest", description = "手工出账请求（注意：本请求体字段是 camelCase）")
public record AdminChargeRequest(

        @Schema(description = "门户用户 UUID（门户 JWT 的 sub）；本侧据此解析/建立影子本地用户",
                example = "018f3a2b-7c1d-7000-9e21-3f9a2b8c1d00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "portalUserId 不能为空") String portalUserId,

        @Schema(description = "出账金额，单位**分**（必须 > 0）", example = "3000",
                minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "amountCents 不能为空") @Min(value = 1, message = "金额必须大于 0 分") Long amountCents,

        @Schema(description = "账单类型：`1` 续费 / `2` 按量（`3` 退款由退款接口生成，此处不可传）",
                example = "2", allowableValues = {"1", "2"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "type 不能为空") Integer type,

        @Schema(description = "关联业务单号（可空），仅用于溯源", example = "verify-001")
        String relatedBillNo) {
}
