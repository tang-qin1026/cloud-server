package com.company.cloud.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.cloud.portal.dto.ProductRow;
import com.company.cloud.portal.entity.PortalProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * portal_product_sku 数据访问。
 */
@Mapper
public interface PortalProductSkuMapper extends BaseMapper<PortalProductSku> {

    /**
     * 门户商品目录（契约 §3.1）：只返回在售 SKU，一次全量、不分页。
     * spec 用 {@code spec::text} 取出，由应用层解析为 JsonNode 原样透传给门户。
     */
    @Select("""
            SELECT sku_id, name, spec::text AS spec_json, price_cents,
                   billing_mode, period_days, stock
            FROM portal_product_sku
            WHERE is_active = TRUE
            ORDER BY sort_no, id
            """)
    List<ProductRow> selectActiveRows();

    /**
     * 开通时按 sku_id 取在售 SKU（含产品侧私有的 quota_gb）。
     * 取不到即视为「sku 不存在/已下架」，门户侧对应 code=4003。
     */
    @Select("""
            SELECT id, sku_id, name, price_cents, billing_mode, period_days, stock, quota_gb, is_active
            FROM portal_product_sku
            WHERE sku_id = #{skuId} AND is_active = TRUE
            """)
    PortalProductSku selectActiveBySkuId(@Param("skuId") String skuId);

    /**
     * 原子扣库存（仅对 stock IS NOT NULL 的 SKU 生效）。
     * 返回 0 表示库存不足或 SKU 不限库存以外的异常，调用方需据此判定 code=4004。
     */
    @Update("""
            UPDATE portal_product_sku
            SET stock = stock - 1, updated_at = now()
            WHERE sku_id = #{skuId} AND is_active = TRUE
              AND stock IS NOT NULL AND stock > 0
            """)
    int decreaseStockIfLimited(@Param("skuId") String skuId);

    /** 开通失败时回补库存（尽力而为，不影响主结果）。 */
    @Update("""
            UPDATE portal_product_sku
            SET stock = stock + 1, updated_at = now()
            WHERE sku_id = #{skuId} AND stock IS NOT NULL
            """)
    int increaseStock(@Param("skuId") String skuId);
}
