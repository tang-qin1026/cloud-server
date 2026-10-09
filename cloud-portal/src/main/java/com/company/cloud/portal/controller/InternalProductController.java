package com.company.cloud.portal.controller;

import com.company.cloud.common.result.Result;
import com.company.cloud.portal.dto.ProductListVO;
import com.company.cloud.portal.service.PortalProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内网接口 ①：商品目录（契约 §3.1）。
 *
 * <pre>GET /api/internal/products</pre>
 *
 * <p>鉴权：{@code X-Internal-Key}（由 InternalKeyFilter 在 Security 链之前完成）。
 * 响应套 {@code {code, data}} 外壳，与门户响应结构一致。
 *
 * <p>门户每 10 分钟拉取一次并全量 upsert 到 {@code cloud_portal.p_product}；
 * 本接口**无状态、无分页**，一次全量返回。
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProductController {

    private final PortalProductService productService;

    @GetMapping("/products")
    public Result<ProductListVO> products() {
        ProductListVO vo = productService.listActive();
        log.debug("[portal] 商品目录被拉取，共 {} 个 SKU", vo.products().size());
        return Result.ok(vo);
    }
}
