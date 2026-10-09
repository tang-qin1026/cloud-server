package com.company.cloud.portal.dto;

import lombok.Data;

/**
 * 商品目录查询的扁平投影：把 JSONB 的 spec 取成文本，避免 MyBatis 直接映射 jsonb 列。
 */
@Data
public class ProductRow {

    private String skuId;

    private String name;

    /** spec 的 JSON 原文（SQL 里 spec::text AS spec_json）。 */
    private String specJson;

    private Long priceCents;

    private Integer billingMode;

    private Integer periodDays;

    private Integer stock;
}
