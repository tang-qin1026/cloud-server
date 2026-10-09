package com.company.cloud.portal.controller;

import com.company.cloud.common.result.Result;
import com.company.cloud.portal.dto.ProvisionRequest;
import com.company.cloud.portal.dto.ProvisionVO;
import com.company.cloud.portal.entity.PortalProvision;
import com.company.cloud.portal.service.PortalProvisionService;
import com.company.cloud.portal.support.PortalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
public class InternalProvisionController {

    private final PortalProvisionService provisionService;

    @PostMapping("/provision")
    public Result<ProvisionVO> provision(@RequestBody ProvisionRequest req) {
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
