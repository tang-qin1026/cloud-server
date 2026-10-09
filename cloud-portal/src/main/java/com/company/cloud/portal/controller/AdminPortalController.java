package com.company.cloud.portal.controller;

import com.company.cloud.auth.portal.PortalShadowUserService;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.auth.security.RequireRole;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.portal.dto.AdminChargeRequest;
import com.company.cloud.portal.dto.AdminRefundRequest;
import com.company.cloud.portal.service.PortalBillService;
import com.company.cloud.portal.service.ReconciliationReportTask;
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
public class AdminPortalController {

    private final PortalBillService billService;
    private final ReconciliationReportTask reconciliationReportTask;
    private final PortalShadowUserService shadowUserService;

    /**
     * 手工出账并扣费（先落库 status=0，再调门户 /wallet/deduct）。
     *
     * <p>⚠️ 门户持续系统失败时会按 1s/5s/30s 退避重试，本接口最坏约 36 秒才返回。
     */
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
    @PostMapping("/reconciliation/report")
    public Result<Map<String, Object>> report(
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
