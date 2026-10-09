package com.company.cloud.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * GET /internal/products 的 data 体：{ "products": [ ... ] }（契约 §3.1）。
 */
@Schema(name = "ProductListVO", description = "商品目录（一次全量返回，无分页）")
public record ProductListVO(

        @Schema(description = "在售 SKU 列表。门户只 upsert 这些；未上报的 sku 会被门户置 is_active=0 软下架",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<ProductItem> products) {
}
