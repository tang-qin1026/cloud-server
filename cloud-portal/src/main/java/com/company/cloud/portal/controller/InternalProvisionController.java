package com.company.cloud.portal.controller;

import com.company.cloud.common.result.Result;
import com.company.cloud.portal.config.OpenApiConfig;
import com.company.cloud.portal.dto.ProvisionRequest;
import com.company.cloud.portal.dto.ProvisionVO;
import com.company.cloud.portal.entity.PortalProvision;
import com.company.cloud.portal.service.PortalProvisionService;
import com.company.cloud.portal.support.PortalApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内网接口 ②：开通（契约 §3.2）。
 *
 * <pre>POST /api/internal/provision</pre>
 *
 * <p>鉴权：{@code X-Internal-Key}（由 InternalKeyFilter 完成）。
 *
 * <p><b>HTTP 状态码一律 200</b>：失败用 body 里的 {@code code} 表达
 * （4003 sku 不存在 / 4004 库存不足 / 5001 内部错误）。
 * 契约 §9 明确「门户要求一律 HTTP 200」，POST 默认的 201 会让门户判错，
 * 因此这里刻意用 try/catch 把业务失败翻译成 200 + Result，而不是抛异常走全局处理器。
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Tag(name = "门户内网接口",
        description = "供门户调用的内网接口（鉴权：请求头 X-Internal-Key）。"
                + "门户每 10 分钟拉取商品目录；用户支付后调用开通。测试前请点右上角 Authorize 填入内部密钥。")
public class InternalProvisionController {

    private final PortalProvisionService provisionService;

    @Operation(
            summary = "② 开通（幂等，order_item_id 为幂等键）",
            description = """
                    门户在用户支付成功后调用，为本产品开通实例（云存储＝给该用户增加存储配额）。

                    ### 两条铁律（后端已实现，联调时请据此验证）
                    1. **`order_item_id` 是幂等键**：有持久化唯一索引，
                       同一个 `order_item_id` **只会开通一次**，重复调用直接返回首次结果（含相同 `instance_id`）。
                    2. **先落库再建实例**：服务端先写 provision 记录再授予配额，
                       否则门户超时重试会开出两个实例。

                    ### HTTP 状态码恒为 200，成败看 body 的 `code`
                    | code | 场景 | 门户行为 |
                    |---|---|---|
                    | `0` | 成功（或幂等命中首次结果） | 正常 |
                    | `4003` | sku 不存在/已下架 | **不重试**，立即退款 |
                    | `4004` | 库存不足 | **不重试**，立即退款 |
                    | `5001` | 内部错误（含 `quantity` 越界、开通进行中） | **会重试**（最多 3 次），用尽后自动退款 |

                    ### 入参约束
                    - `quantity`：**1~1000**（缺省 1）。越界返回 `5001`，不会误授予资源。
                    - 单笔授予总量上限 1 PiB。
                    - `period_days`：不传则用 SKU 自身的 `period_days`。
                    """,
            security = @SecurityRequirement(name = OpenApiConfig.SEC_INTERNAL_KEY),
            requestBody = @RequestBody(required = true, description = "开通请求（字段名 snake_case）",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "最小请求", value = """
                                    {
                                      "order_item_id": 50001,
                                      "portal_user_id": "018f3a2b-7c1d-7000-9e21-3f9a2b8c1d00",
                                      "sku_id": "storage-500g",
                                      "quantity": 1,
                                      "period_days": 30
                                    }
                                    """),
                            @ExampleObject(name = "带 extra（原样落库）", value = """
                                    {
                                      "order_item_id": 50002,
                                      "portal_user_id": "018f3a2b-7c1d-7000-9e21-3f9a2b8c1d00",
                                      "sku_id": "storage-100g",
                                      "quantity": 2,
                                      "period_days": 90,
                                      "extra": {"渠道": "小程序", "orderNo": "A-1"}
                                    }
                                    """)
                    })))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTTP 200 恒定；成功时 body.code=0",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "成功",
                            value = """
                                    {
                                      "code": 0,
                                      "message": "ok",
                                      "data": {
                                        "instance_id": "st-000001",
                                        "expire_at": "2026-11-08T02:42:19Z",
                                        "quantity": 1
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "内部密钥缺失或错误",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            value = "{\"code\":40001,\"message\":\"无效的内部调用凭证\",\"data\":null}")))
    })
    @PostMapping("/provision")
    public Result<ProvisionVO> provision(
            @org.springframework.web.bind.annotation.RequestBody ProvisionRequest req) {
        Long orderItemId = req == null ? null : req.orderItemId();
        try {
            PortalProvision p = provisionService.provision(req);
            return Result.ok(ProvisionVO.of(p));
        } catch (PortalApiException e) {
            // 门户按 code 决策：4003/4004 不重试立即退款；5001 会重试最多 3 次
            log.warn("[portal] provision 业务失败 orderItemId={} code={} msg={}",
                    orderItemId, e.getCode(), e.getMessage());
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[portal] provision 未预期异常 orderItemId={}", orderItemId, e);
            return Result.error(5001, "内部错误：" + e.getMessage());
        }
    }
}
