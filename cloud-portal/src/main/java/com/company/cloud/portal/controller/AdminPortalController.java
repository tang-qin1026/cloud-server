package com.company.cloud.portal.controller;

import com.company.cloud.auth.portal.PortalShadowUserService;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.auth.security.RequireRole;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.portal.config.OpenApiConfig;
import com.company.cloud.portal.dto.AdminChargeRequest;
import com.company.cloud.portal.dto.AdminRefundRequest;
import com.company.cloud.portal.service.PortalBillService;
import com.company.cloud.portal.service.ReconciliationReportTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端 · 门户接入运维接口（需 admin 角色）。
 *
 * <p>用途：让「先落库再扣费」「退款幂等」「对账上报」这三套机制**可手工触发、可验证**，
 * 而不需要等到真实的按量出账或次日凌晨定时任务。全部走 A 组的
 * {@code @RequireRole("admin")} 鉴权（JWT + RolesInterceptor）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/portal")
@RequireRole("admin")
@RequiredArgsConstructor
@Tag(name = "门户运维接口",
        description = "管理端手工触发（鉴权：Bearer JWT + admin 角色）：出账扣费、退款、补报对账。"
                + "用于验证「先落库再扣费」等机制，无需等定时任务。先调 /api/auth/login 拿 accessToken 再点 Authorize。")
@SecurityRequirement(name = OpenApiConfig.SEC_BEARER)
public class AdminPortalController {

    private final PortalBillService billService;
    private final ReconciliationReportTask reconciliationReportTask;
    private final PortalShadowUserService shadowUserService;

    /**
     * 手工出账并扣费（先落库 status=0，再调门户 /wallet/deduct）。
     *
     * <p>⚠️ 门户持续系统失败时会按 1s/5s/30s 退避重试，本接口最坏约 36 秒才返回。
     */
    @Operation(
            summary = "手工出账并扣费（验证「先落库再扣费」）",
            description = """
                    完整走一遍 `b_bill` 的扣费流程，用于在**不需要真实按量出账**的情况下验证机制：

                    1. 生成账单号（`{product_code}-{yyyyMMdd}-{6位序列}`）并以 `status=0`（待扣）**先落库**；
                    2. 调门户 `POST /wallet/deduct`（带 `X-Internal-Key`）；
                    3. 按响应码收敛状态：

                    | 门户 code | 本侧处理 | 账单状态 |
                    |---|---|---|
                    | `0` | 成功 | `1` 已入账 |
                    | `3003` | **当成功处理**（幂等重复，取首次结果） | `1` 已入账 |
                    | `3001` 余额不足 / `3002` 钱包冻结 | **不重试** | 保持 `0` 待扣，记 `fail_code` |
                    | 网络超时 / 5xx | **用同一个 bill_no 退避重试**（默认 1s/5s/30s） | 用尽后保持 `0`，`fail_code=5001` |

                    ### 怎么验证「先落库再扣费」
                    把门户地址指向一个不可达端口，然后调本接口：
                    **响应是失败，但 `b_bill` 里一定有一条 `status=0` 的记录**（钱没扣成，账记得住，可对账可补扣）。

                    ⚠️ 门户持续系统失败时本接口最坏约 36 秒才返回（退避重试所致）。
                    可用环境变量 `PORTAL_DEDUCT_RETRY_DELAYS_MS` 缩短，如 `100,100,100`。
                    """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "HTTP 200 恒定，结果看 data.settled / data.portalCode",
            content = @Content(mediaType = "application/json", examples = {
                    @ExampleObject(name = "扣费成功", value = """
                            {"code":0,"message":"ok","data":{"billNo":"storage-20261009-000002",
                             "settled":true,"portalCode":0,"message":"ok","retryCount":0}}
                            """),
                    @ExampleObject(name = "余额不足（不重试）", value = """
                            {"code":0,"message":"ok","data":{"billNo":"storage-20261009-000004",
                             "settled":false,"portalCode":3001,"message":"余额不足","retryCount":0}}
                            """),
                    @ExampleObject(name = "门户不可达（已重试3次，账单仍落库）", value = """
                            {"code":0,"message":"ok","data":{"billNo":"storage-20261009-000001",
                             "settled":false,"portalCode":5001,"message":"扣费系统失败，已重试 3 次：...","retryCount":3}}
                            """)
            })))
    @PostMapping("/bills/charge")
    public Result<Map<String, Object>> charge(@Valid @RequestBody AdminChargeRequest req) {
        if (req.type() != PortalBillService.TYPE_RENEW && req.type() != PortalBillService.TYPE_POSTPAID) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 只能是 1（续费）或 2（按量）");
        }
        CurrentUser localUser = shadowUserService.resolveForProvision(req.portalUserId());
        PortalBillService.ChargeResult result = billService.charge(
                req.portalUserId(), localUser.getId(), req.amountCents(), req.type(), req.relatedBillNo());

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("billNo", result.billNo());
        vo.put("settled", result.settled());
        vo.put("portalCode", result.portalCode());
        vo.put("message", result.message());
        vo.put("retryCount", result.retryCount());
        return Result.ok(vo);
    }

    /** 退款（先落库退款单，再调门户 /wallet/refund；累计退款不得超原单金额）。 */
    @Operation(
            summary = "退款（幂等，累计不得超原单金额）",
            description = """
                    1. 以 `type=3`（退款）、`status=0` **先落库**一张退款单（`refund_bill_no` 是退款幂等键）；
                    2. 调门户 `POST /wallet/refund`；
                    3. 成功后退款单置 `status=1`；**累计退清**时把原单置为 `status=2`（已退款），
                       部分退款则原单保持 `status=1`。

                    本侧会先做「累计退款不得超过原单金额」的前置校验（门户侧也会拦，返回 `3004`）。

                    返回的 `portalCode`：`0` 成功 / `3004` 超退（message 里给出**还能退多少分**）/ `3005` 原单不存在。
                    """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "HTTP 200 恒定，结果看 data.settled / data.portalCode",
            content = @Content(mediaType = "application/json", examples = {
                    @ExampleObject(name = "退款成功", value = """
                            {"code":0,"message":"ok","data":{"refundBillNo":"storage-20261009-000005",
                             "settled":true,"portalCode":0,"message":"ok"}}
                            """),
                    @ExampleObject(name = "累计超退被拦", value = """
                            {"code":0,"message":"ok","data":{"refundBillNo":null,
                             "settled":false,"portalCode":3004,"message":"累计退款超过原单金额，最多还能退 600 分"}}
                            """)
            })))
    @PostMapping("/bills/refund")
    public Result<Map<String, Object>> refund(@Valid @RequestBody AdminRefundRequest req) {
        PortalBillService.RefundResult result = billService.refund(
                req.billNo(), req.amountCents(), req.reason() == null ? "管理端发起退款" : req.reason());

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("refundBillNo", result.refundBillNo());
        vo.put("settled", result.settled());
        vo.put("portalCode", result.portalCode());
        vo.put("message", result.message());
        return Result.ok(vo);
    }

    /**
     * 手工补报对账（对账上报幂等，重复上报覆盖金额）。
     *
     * @param date 账期，缺省为昨天
     */
    @Operation(
            summary = "手工补报当日账单到门户（对账用）",
            description = """
                    门户的日对账是「你的账单 vs 门户流水」逐笔比对，但门户**不直连你的库**，
                    因此需要把账单推给门户 `POST /api/internal/reconciliation/report`。

                    - **幂等**：门户按 `(biz_date, product_code, bill_no)` 唯一，重复上报覆盖金额，可安全重放。
                    - **上报范围**：只上报发生过真实资金流水的账单（`status=1` 已入账 / `status=2` 已退款）。
                      `status=0` 的待扣账单门户侧没有流水，上报只会制造噪声差异；
                      其数量通过返回的 `pendingCount` 告知（可用于发现长期卡住未扣的账单）。
                    - 定时任务默认每日 06:00 自动上报前一日，本接口用于**手工补报/测试**。
                    """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "上报结果（accepted 表示门户已接收）",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(
                    value = """
                            {"code":0,"message":"ok","data":{"bizDate":"2026-10-09",
                             "billCount":3,"pendingCount":2,"accepted":true,"message":"ok"}}
                            """))))
    @PostMapping("/reconciliation/report")
    public Result<Map<String, Object>> report(
            @Parameter(description = "账期（ISO 日期，如 2026-10-09）；缺省为昨天", example = "2026-10-09")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        LocalDate bizDate = date == null ? LocalDate.now().minusDays(1) : date;
        ReconciliationReportTask.ReportResult result = reconciliationReportTask.report(bizDate);
        log.info("[portal] 管理端手工对账上报 bizDate={} operator={}", bizDate, operatorName(authentication));

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("bizDate", result.bizDate().toString());
        vo.put("billCount", result.billCount());
        vo.put("pendingCount", result.pendingCount());
        vo.put("accepted", result.accepted());
        vo.put("message", result.message());
        return Result.ok(vo);
    }

    /** 当前门户凭证与配置速览（排障用，不返回密钥明文）。 */
    @Operation(
            summary = "门户接入状态速览（排障）",
            description = """
                    仅返回一个提示串，**不返回任何密钥明文**。真正的连通性请用：
                    - `GET /api/internal/products`（验证 `X-Internal-Key` 与门户拉取链路）
                    - `POST /api/admin/portal/reconciliation/report`（验证本侧 → 门户的出站链路）
                    """)
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("note", "门户侧连通性与密钥配置请通过 /internal/products 与 /admin/portal/reconciliation/report 验证");
        return Result.ok(vo);
    }

    private String operatorName(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu.getUsername();
        }
        return "unknown";
    }
}
