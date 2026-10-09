package com.company.cloud.portal.service;

import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalTransportException;
import com.company.cloud.portal.client.PortalWalletClient;
import com.company.cloud.portal.config.PortalProperties;
import com.company.cloud.portal.entity.PortalBill;
import com.company.cloud.portal.mapper.PortalBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 账单与扣费（契约 §4.2 / §4.3 / §4.4）。
 *
 * <p><b>先落库再扣费</b>是本类存在的全部理由：
 * 先生成 bill_no 落库（status=0 待扣），再调门户 {@code POST /wallet/deduct}。
 * 如果先调门户再落库，一旦写完库前进程挂了，本侧没有账单记录、对面钱已扣，
 * 对账时这笔钱永远说不清。先落库，最坏情况是「有一笔待扣账单没扣成」，可修。
 *
 * <p>失败分流（契约 §4.3）：
 * <ul>
 *   <li>{@code 0}    → status=1 已入账</li>
 *   <li>{@code 3003} → <b>当成功处理</b>，取首次结果，status=1</li>
 *   <li>{@code 3001} 余额不足 / {@code 3002} 钱包冻结 → <b>不重试</b>，保持 status=0 并记 fail_code</li>
 *   <li>网络超时 / 5xx → <b>用同一个 bill_no</b> 退避重试（默认 1s/5s/30s，共 3 次）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalBillService {

    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 账单类型（契约 §4.2）。 */
    public static final int TYPE_RENEW = 1;
    public static final int TYPE_POSTPAID = 2;
    public static final int TYPE_REFUND = 3;

    /** 账单状态（契约 §4.2）。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SETTLED = 1;
    public static final int STATUS_REFUNDED = 2;

    /** 门户码值。 */
    private static final int CODE_INSUFFICIENT = 3001;
    private static final int CODE_WALLET_FROZEN = 3002;
    private static final int CODE_REFUND_EXCEED = 3004;
    private static final int CODE_ORIGINAL_NOT_FOUND = 3005;
    private static final int CODE_SYSTEM_FAILURE = 5001;

    private final PortalBillMapper billMapper;
    private final PortalWalletClient walletClient;
    private final PortalProperties props;

    /** 出账结果。 */
    public record ChargeResult(String billNo, boolean settled, int portalCode, String message, int retryCount) {
    }

    /** 退款结果。 */
    public record RefundResult(String refundBillNo, boolean settled, int portalCode, String message) {
    }

    /**
     * 出账并扣费：先落库（status=0）→ 调门户 → 按响应码收敛状态。
     *
     * <p><b>注意</b>：门户持续系统失败时会按 1s/5s/30s 退避重试，最坏约 36 秒才返回。
     * 调用方若在 HTTP 请求线程里用它，要有相应超时预期（定时任务无此顾虑）。
     */
    public ChargeResult charge(String portalUserId, Long localUserId, long amountCents, int type, String relatedBillNo) {
        String billNo = generateBillNo();

        // ── 第 1 步：先落库（必须在这步就写库，status=0 待扣）───────────────
        PortalBill bill = new PortalBill();
        bill.setBillNo(billNo);
        bill.setLocalUserId(localUserId);
        bill.setPortalUserId(portalUserId);
        bill.setProductCode(props.getProductCode());
        bill.setAmountCents(amountCents);
        bill.setType(type);
        bill.setRelatedBillNo(relatedBillNo);
        bill.setStatus(STATUS_PENDING);
        bill.setRetryCount(0);
        bill.setBizDate(LocalDate.now());
        billMapper.insert(bill);

        // ── 第 2 步：调用门户（系统类失败重试，业务类失败立即返回）─────────
        List<Integer> delays = props.getDeductRetryDelaysMs();
        int attempts = 0;
        while (true) {
            try {
                PortalApiResponse resp = walletClient.deduct(billNo, portalUserId, amountCents, type);
                if (resp.success()) {
                    // code=0 成功；code=3003 幂等重复 —— 两者都当成功处理
                    billMapper.updateResult(billNo, STATUS_SETTLED, null, attempts);
                    log.info("[portal] 扣费成功 billNo={} amount={} type={} portalCode={} retries={}",
                            billNo, amountCents, type, resp.code(), attempts);
                    return new ChargeResult(billNo, true, resp.code(), resp.message(), attempts);
                }
                if (resp.businessFailure()) {
                    // 3001/3002：不重试，账单保持 0，走产品侧自己的宽限流程
                    billMapper.updateResult(billNo, STATUS_PENDING, resp.code(), attempts);
                    log.warn("[portal] 扣费业务失败（不重试）billNo={} portalCode={} msg={}",
                            billNo, resp.code(), resp.message());
                    return new ChargeResult(billNo, false, resp.code(), resp.message(), attempts);
                }
                // 其它未预期业务码：同样不重试，记录后交由人工/对账发现
                billMapper.updateResult(billNo, STATUS_PENDING, resp.code(), attempts);
                log.warn("[portal] 扣费返回未预期码 billNo={} portalCode={} msg={}",
                        billNo, resp.code(), resp.message());
                return new ChargeResult(billNo, false, resp.code(), resp.message(), attempts);

            } catch (PortalTransportException e) {
                if (attempts >= delays.size()) {
                    billMapper.updateResult(billNo, STATUS_PENDING, CODE_SYSTEM_FAILURE, attempts);
                    log.error("[portal] 扣费系统失败已达重试上限 billNo={} retries={} err={}",
                            billNo, attempts, e.getMessage());
                    return new ChargeResult(billNo, false, CODE_SYSTEM_FAILURE,
                            "扣费系统失败，已重试 " + attempts + " 次：" + e.getMessage(), attempts);
                }
                int delay = delays.get(attempts);
                log.warn("[portal] 扣费系统失败，{} ms 后用同一 billNo 重试（第 {} 次）billNo={} err={}",
                        delay, attempts + 1, billNo, e.getMessage());
                sleepQuietly(delay);
                attempts++;
            }
        }
    }

    /**
     * 退款：先落库（type=3、status=0）→ 调门户 → 收敛。
     *
     * <p>本侧先做「累计退款不得超过原单金额」的前置校验；门户侧同样会拦（返回 3004）。
     * 全部退清时把原单置为 status=2（已退款）。
     */
    public RefundResult refund(String originalBillNo, long amountCents, String reason) {
        PortalBill original = billMapper.selectByBillNo(originalBillNo);
        if (original == null) {
            return new RefundResult(null, false, CODE_ORIGINAL_NOT_FOUND, "原单不存在: " + originalBillNo);
        }

        long alreadyRefunded = billMapper.sumRefunded(originalBillNo);
        long refundable = original.getAmountCents() - alreadyRefunded;
        if (amountCents > refundable) {
            return new RefundResult(null, false, CODE_REFUND_EXCEED,
                    "累计退款超过原单金额，最多还能退 " + refundable + " 分");
        }

        String refundBillNo = generateBillNo();

        // ── 先落库：退款幂等键 refund_bill_no ────────────────────────────
        PortalBill refundBill = new PortalBill();
        refundBill.setBillNo(refundBillNo);
        refundBill.setLocalUserId(original.getLocalUserId());
        refundBill.setPortalUserId(original.getPortalUserId());
        refundBill.setProductCode(original.getProductCode());
        refundBill.setAmountCents(amountCents);
        refundBill.setType(TYPE_REFUND);
        refundBill.setRelatedBillNo(originalBillNo);
        refundBill.setStatus(STATUS_PENDING);
        refundBill.setRetryCount(0);
        refundBill.setBizDate(LocalDate.now());
        billMapper.insert(refundBill);

        List<Integer> delays = props.getDeductRetryDelaysMs();
        int attempts = 0;
        while (true) {
            try {
                PortalApiResponse resp = walletClient.refund(originalBillNo, refundBillNo, amountCents,
                        reason, original.getPortalUserId());
                if (resp.success()) {
                    billMapper.updateResult(refundBillNo, STATUS_SETTLED, null, attempts);
                    // 退清则原单置为已退款（部分退款时原单保持已入账）
                    if (alreadyRefunded + amountCents >= original.getAmountCents()) {
                        billMapper.updateResult(originalBillNo, STATUS_REFUNDED, null, 0);
                    }
                    log.info("[portal] 退款成功 originalBillNo={} refundBillNo={} amount={} retries={}",
                            originalBillNo, refundBillNo, amountCents, attempts);
                    return new RefundResult(refundBillNo, true, resp.code(), resp.message());
                }
                // 3004 / 3005 等业务失败：不重试
                billMapper.updateResult(refundBillNo, STATUS_PENDING, resp.code(), attempts);
                log.warn("[portal] 退款业务失败（不重试）refundBillNo={} portalCode={} msg={}",
                        refundBillNo, resp.code(), resp.message());
                return new RefundResult(refundBillNo, false, resp.code(), resp.message());

            } catch (PortalTransportException e) {
                if (attempts >= delays.size()) {
                    billMapper.updateResult(refundBillNo, STATUS_PENDING, CODE_SYSTEM_FAILURE, attempts);
                    log.error("[portal] 退款系统失败已达重试上限 refundBillNo={} retries={} err={}",
                            refundBillNo, attempts, e.getMessage());
                    return new RefundResult(refundBillNo, false, CODE_SYSTEM_FAILURE,
                            "退款系统失败，已重试 " + attempts + " 次：" + e.getMessage());
                }
                int delay = delays.get(attempts);
                log.warn("[portal] 退款系统失败，{} ms 后用同一 refundBillNo 重试（第 {} 次）refundBillNo={}",
                        delay, attempts + 1, refundBillNo);
                sleepQuietly(delay);
                attempts++;
            }
        }
    }

    /** 账单号：{product_code}-{yyyyMMdd}-{6 位序列}，例如 storage-20261008-000123。 */
    private String generateBillNo() {
        return props.getProductCode() + "-" + LocalDate.now().format(BILL_DATE)
                + "-" + String.format("%06d", billMapper.nextBillSeq());
    }

    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
