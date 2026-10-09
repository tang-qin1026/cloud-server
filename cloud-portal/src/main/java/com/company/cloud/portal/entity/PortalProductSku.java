package com.company.cloud.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 门户商品目录（portal_product_sku）。本表是商品的**权威来源**。
 *
 * <p>{@code spec} 是 JSONB，读写统一走自定义 SQL（读取时 {@code spec::text}），
 * 因此这里标 {@code select=false}，避免 BaseMapper 直接取 jsonb 列。
 */
@Data
@TableName("portal_product_sku")
public class PortalProductSku {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 产品内唯一，变更即视为下架旧 SKU。 */
    private String skuId;

    private String name;

    /** JSONB 原文；仅自定义 SQL 使用。 */
    @TableField(value = "spec", select = false)
    private String spec;

    /** 单价（分）。 */
    private Long priceCents;

    /** 1 包周期 / 2 按量。 */
    private Integer billingMode;

    private Integer periodDays;

    /** NULL = 不限库存。 */
    private Integer stock;

    /** 开通时授予的配额（GB），产品侧私有字段。 */
    private Integer quotaGb;

    private Integer sortNo;

    private Boolean isActive;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
