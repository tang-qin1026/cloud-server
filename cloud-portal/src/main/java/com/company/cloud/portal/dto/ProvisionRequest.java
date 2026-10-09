package com.company.cloud.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST /internal/provision 请求体（契约 §3.2）。
 */
public record ProvisionRequest(
        @JsonProperty("order_item_id") Long orderItemId,
        @JsonProperty("portal_user_id") String portalUserId,
        @JsonProperty("sku_id") String skuId,
        @JsonProperty("quantity") Integer quantity,
        @JsonProperty("period_days") Integer periodDays,
        @JsonProperty("extra") JsonNode extra) {

    /** 数量缺省 1。 */
    public int quantityOrDefault() {
        return quantity == null || quantity < 1 ? 1 : quantity;
    }
}
