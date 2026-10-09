package com.company.cloud.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 商品目录单项（契约 §3.1）。字段名严格用 snake_case，与门户契约逐字对齐。
 *
 * @param spec 任意结构，门户原样展示（前端按 key 通用渲染，门户不理解你的字段）
 */
public record ProductItem(
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("name") String name,
        @JsonProperty("spec") JsonNode spec,
        @JsonProperty("price_cents") Long priceCents,
        @JsonProperty("billing_mode") Integer billingMode,
        @JsonProperty("period_days") Integer periodDays,
        @JsonProperty("stock") Integer stock) {
}
