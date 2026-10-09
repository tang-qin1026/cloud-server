package com.company.cloud.portal.service;

import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalTransportException;
import com.company.cloud.portal.client.PortalWalletClient;
import com.company.cloud.portal.config.PortalProperties;
import com.company.cloud.portal.entity.PortalBill;
import com.company.cloud.portal.mapper.PortalBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日账单上报（契约 §6）：门户的日对账是「你的账单 vs 门户流水」逐笔比对，
 * 但门户**不直连你的库**，所以需要把当日账单推给门户。
 *
 * <p>幂等：门户按 {@code (biz_date, product_code, bill_no)} 唯一，重复上报覆盖金额，
 * 因此本任务可以安全重试、重复执行。
 *
 * <p>上报范围：只上报发生过真实资金流水的账单（status=1 已入账 / status=2 已退款）。
 * status=0 的待扣账单在门户侧没有流水，报上去只会制造「仅产品侧有」的噪声差异；
 * 但它们的数量会打进日志，便于发现长期卡住未扣的账单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationReportTask {

    private final PortalBillMapper billMapper;
    private final PortalWalletClient walletClient;
    private final PortalProperties props;

    /** 上报结果。 */
    public record ReportResult(LocalDate bizDate, int billCount, long pendingCount, boolean accepted, String message) {
    }

    @Scheduled(cron = "${app.portal.reconcile-cron:0 0 6 * * *}")
    public void reportYesterday() {
        ReportResult result = report(LocalDate.now().minusDays(1));
        log.info("[portal] 每日账单上报 bizDate={} 上报 {} 条（当日待扣 {} 条）accepted={} msg={}",
                result.bizDate(), result.billCount(), result.pendingCount(), result.accepted(), result.message());
    }

    /** 上报指定账期；供定时任务与手工补报（管理端）共用。 */
    public ReportResult report(LocalDate bizDate) {
        String productCode = props.getProductCode();
        List<PortalBill> bills = billMapper.selectReportable(productCode, bizDate);
        long pending = billMapper.countPending(productCode, bizDate);

        List<Map<String, Object>> payload = new ArrayList<>(bills.size());
        for (PortalBill b : bills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bill_no", b.getBillNo());
            item.put("amount_cents", b.getAmountCents());
            item.put("type", b.getType());
            item.put("portal_user_id", b.getPortalUserId());
            payload.add(item);
        }

        if (payload.isEmpty()) {
            return new ReportResult(bizDate, 0, pending, true, "当无可上报账单");
        }

        // 对账上报幂等 ⇒ 系统类失败直接重试
        int attempts = 0;
        int maxRetries = Math.max(0, props.getReconcileRetries());
        while (true) {
            try {
                PortalApiResponse resp = walletClient.reportReconciliation(bizDate, payload);
                if (resp.success()) {
                    return new ReportResult(bizDate, payload.size(), pending, true, resp.message());
                }
                log.warn("[portal] 账单上报业务失败 bizDate={} code={} msg={}", bizDate, resp.code(), resp.message());
                return new ReportResult(bizDate, payload.size(), pending, false,
                        "门户返回 " + resp.code() + ": " + resp.message());
            } catch (PortalTransportException e) {
                if (attempts >= maxRetries) {
                    log.error("[portal] 账单上报失败已达重试上限 bizDate={} retries={} err={}",
                            bizDate, attempts, e.getMessage());
                    return new ReportResult(bizDate, payload.size(), pending, false,
                            "上报失败（已重试 " + attempts + " 次）：" + e.getMessage());
                }
                attempts++;
                long backoff = Math.min(30_000L, 1000L * (1L << (attempts - 1)));
                log.warn("[portal] 账单上报系统失败，{} ms 后重试（第 {} 次）bizDate={}", backoff, attempts, bizDate);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ReportResult(bizDate, payload.size(), pending, false, "被中断");
                }
            }
        }
    }
}
