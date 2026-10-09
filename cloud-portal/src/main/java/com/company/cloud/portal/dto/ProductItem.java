package com.company.cloud.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商品目录单项（契约 §3.1）。字段名严格用 snake_case，与门户契约逐字对齐。
 *
 * @param spec 任意结构，门户原样展示（前端按 key 通用渲染，门户不理解你的字段）
 */
@Schema(name = "ProductItem", description = "商品目录单项（字段名与门户契约 §3.1 逐字对齐）")
public record ProductItem(

        @Schema(description = "产品内唯一的 SKU 标识。**变更即视为下架旧 SKU、上架新 SKU**（历史订单仍指向旧 id）",
                example = "storage-500g", maxLength = 64, requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("sku_id") String skuId,

        @Schema(description = "门户展示名", example = "云存储·500G", maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("name") String name,

        @Schema(description = "规格。**结构完全由本产品自定**，门户原样展示（前端按 key 通用渲染，不要假设门户理解你的字段）",
                example = "{\"quota_gb\": 500, \"transfer\": \"unlimited\", \"period\": \"30天\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("spec") JsonNode spec,

        @Schema(description = "单价，单位**分**（不是元！全链路以分存储，仅展示时除 100）",
                example = "50000", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("price_cents") Long priceCents,

        @Schema(description = "计费模式：`1` 包周期（下单即扣）／`2` 按量（购买时 0 元，产品侧后付费出账）",
                example = "1", allowableValues = {"1", "2"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("billing_mode") Integer billingMode,

        @Schema(description = "周期天数。`billing_mode=1` 时**必填**；`billing_mode=2` 可为空",
                example = "30")
        @JsonProperty("period_days") Integer periodDays,

        @Schema(description = "库存。`null` 表示不限库存或未上报（前端显示「现货」）；云存储即不限库存",
                example = "null", nullable = true)
        @JsonProperty("stock") Integer stock) {
}
