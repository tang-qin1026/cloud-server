package com.company.cloud.portal.dto;

import java.util.List;

/**
 * GET /internal/products 的 data 体：{ "products": [ ... ] }（契约 §3.1）。
 */
public record ProductListVO(List<ProductItem> products) {
}
