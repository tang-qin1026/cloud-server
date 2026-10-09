package com.company.cloud.portal.controller;

import com.company.cloud.common.result.Result;
import com.company.cloud.portal.config.OpenApiConfig;
import com.company.cloud.portal.dto.ProductListVO;
import com.company.cloud.portal.service.PortalProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "门户内网接口",
        description = "供门户调用的内网接口（鉴权：请求头 X-Internal-Key）。"
                + "门户每 10 分钟拉取商品目录；用户支付后调用开通。测试前请点右上角 Authorize 填入内部密钥。")
public class InternalProductController {

    private final PortalProductService productService;

    @Operation(
            summary = "① 商品目录（门户每 10 分钟拉取）",
            description = """
                    返回本产品**全部在售** SKU，门户据此全量 upsert 到它自己的 `p_product` 缓存表。

                    - **无分页**：一次全量返回，门户超时 10 秒，故本接口不做重计算。
                    - **`price_cents` 单位是分**（不是元）。
                    - 不再上报的 sku 会被门户置 `is_active=0` 软下架，因此 **`sku_id` 必须稳定**：
                      改 sku_id 等于下架旧的、上架新的。
                    - `spec` 结构完全由本产品自定，门户原样展示（门户不理解你的字段）。
                    - `stock` 为 `null` 表示不限库存（云存储即如此），前端显示「现货」。
                    """,
            security = @SecurityRequirement(name = OpenApiConfig.SEC_INTERNAL_KEY))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功（注意：业务失败也返回 HTTP 200，靠 body.code 区分）",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "4 个种子 SKU",
                            value = """
                                    {
                                      "code": 0,
                                      "message": "ok",
                                      "data": {
                                        "products": [
                                          {
                                            "sku_id": "storage-100g",
                                            "name": "云存储·100G",
                                            "spec": {"quota_gb": 100, "transfer": "unlimited", "period": "30天"},
                                            "price_cents": 10000,
                                            "billing_mode": 1,
                                            "period_days": 30,
                                            "stock": null
                                          },
                                          {
                                            "sku_id": "storage-500g",
                                            "name": "云存储·500G",
                                            "spec": {"quota_gb": 500, "transfer": "unlimited", "period": "30天"},
                                            "price_cents": 50000,
                                            "billing_mode": 1,
                                            "period_days": 30,
                                            "stock": null
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "内部密钥缺失或错误（未配置 app.portal.internal-key 时同样拒绝）",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            value = "{\"code\":40001,\"message\":\"无效的内部调用凭证\",\"data\":null}")))
    })
    @GetMapping("/products")
    public Result<ProductListVO> products() {
        ProductListVO vo = productService.listActive();
        log.debug("[portal] 商品目录被拉取，共 {} 个 SKU", vo.products().size());
        return Result.ok(vo);
    }
}
