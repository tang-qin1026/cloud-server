package com.company.cloud.portal.service;

import com.company.cloud.portal.dto.ProductItem;
import com.company.cloud.portal.dto.ProductListVO;
import com.company.cloud.portal.dto.ProductRow;
import com.company.cloud.portal.mapper.PortalProductSkuMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品目录（契约 §3.1）。
 *
 * <p>无状态、只读一次全量查询，门户超时 10 秒，因此这里不做任何重计算。
 * 门户每 10 分钟拉取一次并全量 upsert 到它自己的 {@code p_product} 缓存表；
 * 产品调价后最迟 10 分钟生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalProductService {

    private final PortalProductSkuMapper skuMapper;
    private final ObjectMapper objectMapper;

    public ProductListVO listActive() {
        List<ProductRow> rows = skuMapper.selectActiveRows();
        List<ProductItem> items = new ArrayList<>(rows.size());
        for (ProductRow row : rows) {
            items.add(new ProductItem(
                    row.getSkuId(),
                    row.getName(),
                    parseSpec(row.getSkuId(), row.getSpecJson()),
                    row.getPriceCents(),
                    row.getBillingMode(),
                    row.getPeriodDays(),
                    row.getStock()));
        }
        return new ProductListVO(items);
    }

    /** spec 原样透传；脏数据不应导致整个目录接口失败，降级为空对象并告警。 */
    private JsonNode parseSpec(String skuId, String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(specJson);
        } catch (Exception e) {
            log.warn("[portal] sku {} 的 spec 不是合法 JSON，已降级为空对象: {}", skuId, specJson);
            return objectMapper.createObjectNode();
        }
    }
}
