package com.company.cloud.portal.service;

import com.company.cloud.auth.portal.PortalShadowUserService;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.portal.dto.ProvisionRequest;
import com.company.cloud.portal.entity.PortalProductSku;
import com.company.cloud.portal.entity.PortalProvision;
import com.company.cloud.portal.mapper.PortalProductSkuMapper;
import com.company.cloud.portal.mapper.PortalProvisionMapper;
import com.company.cloud.portal.support.PortalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 门户开通（契约 §3.2）。
 *
 * <p><b>两条铁律</b>：
 * <ol>
 *   <li>{@code order_item_id} 有持久化唯一索引（V2022），重复调用直接返回首次结果</li>
 *   <li><b>先落库再执行</b>：先写 provision 记录（状态 pending），再建实例（授予配额）。
 *       否则门户并发重试会开出两个实例</li>
 * </ol>
 *
 * <p><b>为什么不用 @Transactional</b>：本流程是一个显式状态机
 * （pending → granting → active），需要每一步立即提交，才能在崩溃/并发后
 * 被下一次重试正确观察到。若整个方法包在一个大事务里，中途失败会把
 * 「已落库」的证据一起回滚，幂等保护就失效了。这里每条 SQL 自动提交，
 * 语义等价于「每步一个短事务」，比长事务更安全。
 *
 * <p>性能：全程只有若干条单行 SQL，远低于门户 30 秒超时。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalProvisionService {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_FAILED = "failed";

    private final PortalProvisionMapper provisionMapper;
    private final PortalProductSkuMapper skuMapper;
    private final PortalShadowUserService shadowUserService;

    /**
     * 幂等开通。
     *
     * @throws PortalApiException code=4003 sku 不存在/下架；4004 库存不足；5001 内部错误（门户会重试）
     */
    public PortalProvision provision(ProvisionRequest req) {
        if (req.orderItemId() == null) {
            throw PortalApiException.internalError("缺少 order_item_id");
        }
        if (req.portalUserId() == null || req.portalUserId().isBlank()) {
            throw PortalApiException.internalError("缺少 portal_user_id");
        }

        // ── 1. 幂等：同一 order_item_id 直接返回首次结果 ──────────────────
        PortalProvision existing = provisionMapper.selectByOrderItemId(req.orderItemId());
        if (existing != null && !STATUS_FAILED.equals(existing.getStatus())) {
            log.info("[portal] provision 幂等命中 orderItemId={} status={} instanceId={}",
                    req.orderItemId(), existing.getStatus(), existing.getInstanceId());
            return existing;
        }

        // ── 2. SKU 校验（不存在/下架 → 4003，门户不重试直接退款）──────────
        PortalProductSku sku = skuMapper.selectActiveBySkuId(req.skuId());
        if (sku == null) {
            throw PortalApiException.skuNotAvailable(req.skuId());
        }

        int quantity = req.quantityOrDefault();
        boolean stockReserved = false;
        // 只有抢到 granting 的调用方才拥有该记录，失败时才可标记 failed，
        // 否则会把「另一个请求正在建实例」的在途记录标死。
        boolean ownsGranting = false;

        try {
            // ── 3. 库存：仅对有限库存 SKU 生效，原子扣减防超卖 ────────────────
            if (sku.getStock() != null) {
                if (skuMapper.decreaseStockIfLimited(req.skuId()) == 0) {
                    throw PortalApiException.outOfStock(req.skuId());
                }
                stockReserved = true;
            }

            // ── 4. 影子用户（门户用户首次到达业务侧时建档）────────────────
            CurrentUser localUser = shadowUserService.resolveForProvision(req.portalUserId());

            long grantedBytes = (long) (sku.getQuotaGb() == null ? 0 : sku.getQuotaGb()) * quantity * GIB;
            int periodDays = req.periodDays() != null
                    ? req.periodDays()
                    : (sku.getPeriodDays() == null ? 30 : sku.getPeriodDays());

            // ── 5. 先落库（铁律 2）：唯一索引兜底并发 ─────────────────────
            PortalProvision record = new PortalProvision();
            record.setOrderItemId(req.orderItemId());
            record.setPortalUserId(req.portalUserId());
            record.setLocalUserId(localUser.getId());
            record.setSkuId(req.skuId());
            record.setQuantity(quantity);
            record.setPeriodDays(periodDays);
            record.setGrantedBytes(grantedBytes);
            record.setStatus(STATUS_PENDING);
            record.setQuotaReclaimed(false);
            record.setExtra(req.extra() == null ? null : req.extra().toString());
            try {
                provisionMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发重试：另一个请求已插入同一 order_item_id，回查并返回它的结果
                PortalProvision first = provisionMapper.selectByOrderItemId(req.orderItemId());
                if (first != null && !STATUS_FAILED.equals(first.getStatus())) {
                    log.info("[portal] provision 并发冲突，返回首次结果 orderItemId={}", req.orderItemId());
                    return first;
                }
                throw PortalApiException.internalError("开通记录冲突，请重试");
            }

            // ── 6. 抢占授予权：只有 pending → granting 的胜者建实例 ──────────
            if (provisionMapper.claim(req.orderItemId()) == 0) {
                PortalProvision first = provisionMapper.selectByOrderItemId(req.orderItemId());
                if (first != null && STATUS_ACTIVE.equals(first.getStatus())) {
                    return first;
                }
                // 仍在 granting（另一请求正在建实例）：返回 5001 让门户重试，避免重复授予。
                // 注意此处 ownsGranting=false，不能标记 failed。
                throw PortalApiException.internalError("开通进行中，请稍后重试");
            }
            ownsGranting = true;

            // ── 7. 建实例：授予配额（users.quota_bytes += granted_bytes）────
            if (grantedBytes > 0) {
                provisionMapper.grantQuota(localUser.getId(), grantedBytes);
            }

            String instanceId = "st-" + String.format("%06d", record.getId());
            OffsetDateTime expireAt = OffsetDateTime.now().plusDays(periodDays);

            // ── 8. 收敛为 active ─────────────────────────────────────────
            if (provisionMapper.finalizeActive(req.orderItemId(), instanceId, expireAt) == 0) {
                throw PortalApiException.internalError("开通状态收敛失败，请重试");
            }
            ownsGranting = false; // 已收敛，后续异常不应再回退状态

            PortalProvision done = provisionMapper.selectByOrderItemId(req.orderItemId());
            log.info("[portal] provision 成功 orderItemId={} instanceId={} localUserId={} grantedBytes={} expireAt={}",
                    req.orderItemId(), instanceId, localUser.getId(), grantedBytes, expireAt);
            return done;

        } catch (PortalApiException e) {
            rollbackOnFailure(req, stockReserved, ownsGranting);
            throw e;
        } catch (Exception e) {
            rollbackOnFailure(req, stockReserved, ownsGranting);
            log.error("[portal] provision 内部错误 orderItemId={}", req.orderItemId(), e);
            // 5001：门户会重试（最多 3 次），用尽后自动退款
            throw PortalApiException.internalError("开通失败：" + e.getMessage());
        }
    }

    /** 失败善后：回补库存 + 仅在「本调用方拥有 granting」时把记录置 failed。 */
    private void rollbackOnFailure(ProvisionRequest req, boolean stockReserved, boolean ownsGranting) {
        if (stockReserved) {
            try {
                skuMapper.increaseStock(req.skuId());
            } catch (Exception ex) {
                log.warn("[portal] 回补库存失败 skuId={} err={}", req.skuId(), ex.toString());
            }
        }
        if (ownsGranting) {
            try {
                provisionMapper.markFailed(req.orderItemId());
            } catch (Exception ex) {
                log.warn("[portal] 标记开通失败状态异常 orderItemId={} err={}", req.orderItemId(), ex.toString());
            }
        }
    }
}
