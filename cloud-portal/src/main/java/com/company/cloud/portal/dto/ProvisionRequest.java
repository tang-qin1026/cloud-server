package com.company.cloud.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /internal/provision 请求体（契约 §3.2）。
 */
@Schema(name = "ProvisionRequest", description = "开通请求（字段名 snake_case，与门户契约 §3.2 逐字对齐）")
public record ProvisionRequest(

        @Schema(description = "门户订单项 id。**幂等键**：同一个值重复调用只会开通一次，直接返回首次结果",
                example = "50001", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("order_item_id") Long orderItemId,

        @Schema(description = "门户用户 UUID（门户 JWT 的 sub）。本产品会据此建立/复用影子本地用户",
                example = "018f3a2b-7c1d-7000-9e21-3f9a2b8c1d00", maxLength = 36,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("portal_user_id") String portalUserId,

        @Schema(description = "要开通的 SKU 标识（需存在于商品目录且在售）",
                example = "storage-500g", maxLength = 64, requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("sku_id") String skuId,

        @Schema(description = "数量，**允许范围 1~1000**（缺省 1）。越界返回 `code=5001`，不会误授予资源",
                example = "1", minimum = "1", maximum = "1000", defaultValue = "1")
        @JsonProperty("quantity") Integer quantity,

        @Schema(description = "周期**小时**数（**优先使用本字段**，单位=小时：1 天传 `24`，30 天传 `720`，3 小时传 `3`）。"
                        + "不传时按「`period_days` ×24 → 该 SKU 自身周期 → 720」的顺序兜底",
                example = "720")
        @JsonProperty("period_hours") Integer periodHours,

        @Schema(description = "**兼容字段，单位=天，已弃用**。仅当门户尚未升级、只下发本字段时用于兜底："
                        + "本侧会按 ×24 换算成小时并打 WARN 日志。"
                        + "**不要与 `period_hours` 同时填**；两个都填时以 `period_hours` 为准",
                example = "30")
        @JsonProperty("period_days") Integer periodDays,

        @Schema(description = "门户透传的附加信息，**任意结构**，本侧原样落库到 `portal_provision.extra`",
                example = "{\"渠道\": \"小程序\", \"orderNo\": \"A-1\"}", nullable = true)
        @JsonProperty("extra") JsonNode extra) {

    /** 数量缺省 1。 */
    public int quantityOrDefault() {
        return quantity == null || quantity < 1 ? 1 : quantity;
    }
}
