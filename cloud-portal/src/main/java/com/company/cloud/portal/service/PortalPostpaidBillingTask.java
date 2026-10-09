package com.company.cloud.portal.service;

import com.company.cloud.portal.config.PortalProperties;
import com.company.cloud.portal.entity.PortalProductSku;
import com.company.cloud.portal.entity.PortalProvision;
import com.company.cloud.portal.mapper.PortalProductSkuMapper;
import com.company.cloud.portal.mapper.PortalProvisionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 按量计费（billing_mode=2）后付费出账（契约 §3.1 / §8 自检项）。
 *
 * <p>计费口径（**需业务确认**）：按 SKU 自身公示的 {@code price_cents} 作为一个计费周期
 * （{@code period_days}）的费用，每个周期对每笔在期开通记录出账一次，
 * 金额 = 单价 × quantity。出账即走 {@link PortalBillService#charge}（先落库再扣费）。
 *
 * <p>因为「按量后付」的真实计价规则（按实际用量？按峰值？按天摊？）尚未由业务方定义，
 * 本任务**默认关闭**（{@code app.portal.postpaid.enabled=false}），
 * 待计费口径书面确认后再打开；打开前不会产生任何扣费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalPostpaidBillingTask {

    private final PortalProvisionMapper provisionMapper;
    private final PortalProductSkuMapper skuMapper;
    private final PortalBillService billService;
    private final PortalProperties props;

    @Scheduled(cron = "${app.portal.postpaid.cron:0 30 5 * * *}")
    public void run() {
        if (!props.getPostpaid().isEnabled()) {
            log.debug("[portal] 按量后付出账未启用（app.portal.postpaid.enabled=false），跳过");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<PortalProvision> candidates =
                provisionMapper.selectPostpaidDue(now, props.getPostpaid().getBatchSize());
        if (candidates.isEmpty()) {
            log.info("[portal] 按量后付出账：无候选记录");
            return;
        }

        int billed = 0;
        int skipped = 0;
        for (PortalProvision p : candidates) {
            PortalProductSku sku = skuMapper.selectActiveBySkuId(p.getSkuId());
            if (sku == null || sku.getBillingMode() == null || sku.getBillingMode() != 2) {
                skipped++;
                continue;   // 非按量 / 已下架：不出账
            }
            long amount = safeAmountCents(sku, p);
            if (amount < 0) {
                skipped++;
                continue;
            }
            PortalBillService.ChargeResult result = billService.charge(
                    p.getPortalUserId(), p.getLocalUserId(), amount,
                    PortalBillService.TYPE_POSTPAID, null);
            if (result.settled()) {
                provisionMapper.markBilled(p.getId(), now);
                billed++;
            } else {
                log.warn("[portal] 按量出账未成功 orderItemId={} billNo={} code={} msg={}",
                        p.getOrderItemId(), result.billNo(), result.portalCode(), result.message());
            }
        }
        log.info("[portal] 按量后付出账完成：成功 {} 条，跳过（非按量/下架）{} 条，候选 {} 条",
                billed, skipped, candidates.size());
    }

    /**
     * 安全计算单笔出账金额（分）＝ 单价 × 数量。
     *
     * <p>用 {@link Math#multiplyExact(long, long)} 而不是裸乘法：裸乘法溢出会静默变成负数，
     * 而负金额传给 {@code /wallet/deduct} 语义完全错误（可能被当成退款）。
     *
     * @return 金额；溢出或单价非法时返回 -1，调用方跳过该条并计数
     */
    private long safeAmountCents(PortalProductSku sku, PortalProvision p) {
        long price = sku.getPriceCents() == null ? 0L : sku.getPriceCents();
        long qty = Math.max(1, p.getQuantity() == null ? 1 : p.getQuantity());
        if (price < 0) {
            log.warn("[portal] SKU 单价为负，跳过出账 sku={} priceCents={}", sku.getSkuId(), price);
            return -1L;
        }
        try {
            return Math.multiplyExact(price, qty);
        } catch (ArithmeticException e) {
            log.warn("[portal] 出账金额溢出，跳过 orderItemId={} sku={} priceCents={} quantity={}",
                    p.getOrderItemId(), sku.getSkuId(), price, qty);
            return -1L;
        }
    }
}
