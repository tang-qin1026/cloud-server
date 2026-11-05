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
import java.util.Map;

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

    /**
     * 单笔开通允许的数量上限。
     *
     * <p>与计费模块的 gbCount 1~1000 口径保持一致。**必须限界**：契约没有约束 quantity，
     * 若放任传入 {@code Integer.MAX_VALUE}，{@code quota_gb × quantity × 1024³} 会静默溢出 long。
     * 实测（2026-10-09）：storage-2t（2048 GiB）× 2147483647 溢出为 {@code -2199023255552}，
     * 接口仍返回 code=0 + instance_id，而配额护栏（grantedBytes &gt; 0）把授予整个跳过 ——
     * 结果是「门户已扣费、本侧报告成功、实际什么都没授予」。因此这里在计算前直接拒绝。
     */
    private static final int MAX_QUANTITY = 1000;

    /** 单笔开通授予的配额上限（1 PiB）。防止 quota_gb 被误配成巨值时同样溢出。 */
    private static final long MAX_GRANTED_BYTES = 1L << 50;

    /**
     * 计费周期缺省值，单位<b>小时</b>：720 小时 = 30 天。
     *
     * <p>为什么不是 30：V2024 起 {@code period_hours} 的单位是小时，填 30 会变成 30 小时。
     * 取 720 是为了保持改单位前「缺省 30 天」的语义——门户万一漏传周期，用户拿到的仍是 30 天。
     */
    private static final int DEFAULT_PERIOD_HOURS = 720;
    /** 企业池宽限期（天）：到期后仍可读/可下载，禁止上传。docs/31 §7.1 */
    private static final int POOL_GRACE_DAYS = 7;

    /**
     * 企业池（docs/31）。放在 cloud-auth，供本模块与 cloud-billing 共用同一份实现。
     */
    private final com.company.cloud.auth.enterprise.EnterprisePoolService enterprisePoolService;
    /** 本地推断企业归属用：企业状态与 owner。 */
    private final com.company.cloud.auth.tenant.mapper.EnterpriseStatusMapper enterpriseStatusMapper;
    private final com.company.cloud.auth.org.mapper.OrgWriteMapper orgWriteMapper;
    private final PortalProvisionMapper provisionMapper;
    /** 企业池（续费识别用）。 */
    private final com.company.cloud.auth.enterprise.EnterprisePoolService poolService;
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
            CurrentUser localUser = shadowUserService.resolveForProvisionWithTenant(req.portalUserId());

            // ── 4a. 续费识别（2026-11-02）─────────────────────────────────
            // 续费是"给原有那笔延长一个周期"，门户扣款后仍会回调本接口送一笔**新 order_item**。
            // 若不拦，容量会被重复加一次（用户看到"续费了但份额也翻倍"）。
            // 命中 subscription_renewal 则：不发放额度，只记一条 granted_bytes=0 的开通记录用于对账，
            // 并把它与门户 order_item 关联。
            Long purchaseEntIdEarly = resolvePurchaseEnterpriseId(req, localUser);
            // 个人域也要识别续费（2026-11-05）：否则个人用户续费时，
            // 回调会被当成新购买 —— 既多发一份容量，又在套餐列表里多出一行。
            Map<String, Object> renewal = purchaseEntIdEarly != null && purchaseEntIdEarly > 0L
                    ? poolService.consumeRenewalIfAny(purchaseEntIdEarly, req.skuId(), quantity)
                    : poolService.consumePersonalRenewalIfAny(localUser.getId(), req.skuId(), quantity);
            if (renewal != null) {
                    long renewalId = ((Number) renewal.get("id")).longValue();
                    long extendsHours = ((Number) renewal.get("extend_hours")).longValue();
                    log.info("[portal] provision 命中续费单 orderItemId={} 企业={} 个人={} 原开通={} 已延长 {} 小时，本次不发放额度",
                            req.orderItemId(), purchaseEntIdEarly, localUser.getId(),
                            renewal.get("grant_order_item_id"), extendsHours);
                    poolService.bindRenewalToOrderItem(renewalId, req.orderItemId());

                    PortalProvision record = new PortalProvision();
                    record.setOrderItemId(req.orderItemId());
                    record.setEnterpriseId(purchaseEntIdEarly);
                    record.setPortalUserId(req.portalUserId());
                    record.setLocalUserId(localUser.getId());
                    record.setSkuId(req.skuId());
                    record.setQuantity(quantity);
                    record.setPeriodHours((int) extendsHours);
                    record.setGrantedBytes(0L);        // 续费不新增容量
                    record.setStatus(STATUS_ACTIVE);
                    record.setQuotaReclaimed(false);
                    record.setInstanceId("rnw-" + renewal.get("grant_order_item_id"));
                    record.setExpireAt(((java.sql.Timestamp) renewal.get("expire_at_after")).toInstant()
                            .atOffset(java.time.ZoneOffset.ofHours(8)));
                    record.setExtra("{\"renewalOf\":" + renewal.get("grant_order_item_id") + "}");
                    try {
                        provisionMapper.insert(record);
                    } catch (DuplicateKeyException e) {
                        PortalProvision first = provisionMapper.selectByOrderItemId(req.orderItemId());
                        if (first != null) {
                            return first;
                        }
                        throw PortalApiException.internalError("续费记录冲突，请重试");
                    }
                    return record;
            }

            long grantedBytes = computeGrantedBytes(sku, quantity);

            // ── 4b. 计费周期：单位=小时（V2024 起）────────────────────────
            // 🚨 优先级**必须**是：period_hours > period_days×24 > SKU 自身周期 > 720
            //    兼容期内门户会同时下发两个字段。若这里写反（把 period_days=30 当小时用），
            //    会出现「用户买了 30 天、30 小时就到期」——严重客诉。
            //    见《16 - 计费周期单位变更：天 → 小时》第 11.7 节。
            Integer hoursFromReq = req.periodHours();
            if (hoursFromReq == null && req.periodDays() != null) {
                hoursFromReq = req.periodDays() * 24;
                log.warn("[portal] 开通入参只带 period_days={}，按 ×24 兜底为 {} 小时。"
                                + "请门户确认兼容分支（过渡期结束后应只发 period_hours）",
                        req.periodDays(), hoursFromReq);
            }
            int periodHours = hoursFromReq != null
                    ? hoursFromReq
                    : (sku.getPeriodHours() == null ? DEFAULT_PERIOD_HOURS : sku.getPeriodHours());

            // ── 5. 先落库（铁律 2）：唯一索引兜底并发 ─────────────────────
            PortalProvision record = new PortalProvision();
            record.setOrderItemId(req.orderItemId());
            // 记录这笔开通进池还是进个人（对账/排障时一眼能看出）
            record.setEnterpriseId(resolvePurchaseEnterpriseId(req, localUser));
            record.setPortalUserId(req.portalUserId());
            record.setLocalUserId(localUser.getId());
            record.setSkuId(req.skuId());
            record.setQuantity(quantity);
            record.setPeriodHours(periodHours);
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

            String instanceId = "st-" + String.format("%06d", record.getId());
            // 到期时间按**小时**推进（V2024 起）：720 小时 = 30 天，3 小时 = 3 小时。
            // 注意宽限期（grace）仍是「天」，不在这里，见 PortalProvisionMapper.markDueAsGrace。
            OffsetDateTime expireAt = OffsetDateTime.now().plusHours(periodHours);

            // ── 7. 授予：进企业池 还是 进个人额度 ────────────────────────
            //
            // docs/31：企业购买的空间进**企业池**，由管理员分配给成员，
            // 不再堆到"下单那一个人"头上（否则成员用不了、管理员也分不了）。
            //
            // 判定依据是请求里的 enterprise_id（门户保证：只有 owner/企业管理员
            // 且企业 active 时才带）。为空 = 个人购买，行为与之前完全一致。
            Long purchaseEnterpriseId = resolvePurchaseEnterpriseId(req, localUser);
            if (grantedBytes > 0) {
                if (purchaseEnterpriseId != null) {
                    enterprisePoolService.grant(purchaseEnterpriseId, req.orderItemId(),
                            grantedBytes, expireAt, POOL_GRACE_DAYS);
                    log.info("[portal] 企业池入池 enterpriseId={} orderItemId={} bytes={} expireAt={}"
                                    + "（来源={}）",
                            purchaseEnterpriseId, req.orderItemId(), grantedBytes, expireAt,
                            req.enterpriseId() != null ? "门户下发" : "本地推断");
                } else {
                    provisionMapper.grantQuota(localUser.getId(), grantedBytes);
                }
            }

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

    /**
     * 计算本次开通授予的配额（字节），并做溢出与上限防护。
     *
     * <p>用量除法做上限判断（{@code unitBytes > MAX_GRANTED_BYTES / quantity}）而不是
     * 先乘再比——后者本身就会溢出。quantity ≥ 1 由调用方保证，故除数安全。
     *
     * @throws PortalApiException 5001：quantity 越界或总量超上限。
     *         用 5001 而非 4003/4004，是因为契约里 5001 定义为「内部错误（资源不足等）」，
     *         门户会重试并在用尽后自动退款；这样能保证**绝不误授予资源**且用户拿到退款。
     */
    private long computeGrantedBytes(PortalProductSku sku, int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw PortalApiException.internalError(
                    "quantity 超出允许范围 1~" + MAX_QUANTITY + "，实际=" + quantity);
        }
        long quotaGb = sku.getQuotaGb() == null ? 0L : sku.getQuotaGb();
        if (quotaGb < 0) {
            throw PortalApiException.internalError("SKU 配额配置非法（quota_gb=" + quotaGb + "），sku=" + sku.getSkuId());
        }
        long unitBytes = quotaGb * GIB;   // quota_gb 为 int（≤2^31-1），乘 2^30 不会溢出 long
        if (unitBytes > MAX_GRANTED_BYTES / quantity) {
            throw PortalApiException.internalError(
                    "开通总量超出单笔上限 " + MAX_GRANTED_BYTES + " 字节：sku=" + sku.getSkuId()
                            + " quota_gb=" + quotaGb + " quantity=" + quantity);
        }
        long granted = unitBytes * quantity;
        if (granted < 0) {
            // 理论不可达（上面已限界），留作最后一道断言，避免无护栏的乘法回归
            throw PortalApiException.internalError("授予配额计算溢出，已拒绝：sku=" + sku.getSkuId());
        }
        return granted;
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
    /**
     * 判定这笔购买该进**企业池**还是**个人额度**（docs/31 §8）。
     *
     * <p>规则：下单人属于 **active 企业** 且 是 **owner / 企业管理员** → 企业池；否则个人。
     *
     * <p><b>两种来源</b>：
     * <ol>
     *   <li>门户在请求里下发 {@code enterprise_id} → 直接采信（门户是权威，
     *       它才知道下单时的实时归属）</li>
     *   <li>门户没下发 → **本地推断**（企业归属、role、owner、企业状态我们本地都有投影）。
     *       这样即便门户还没升级到带这个字段，企业购买也能正确进池，
     *       不会堆到下单人个人额度上。</li>
     * </ol>
     *
     * <p>为什么不只等门户：企业购买若进了个人额度，成员完全用不了，
     * 管理员也分不了 —— 等于功能不可用。能自己判就先自己判。
     */
    private Long resolvePurchaseEnterpriseId(ProvisionRequest req, CurrentUser localUser) {
        if (req.enterpriseId() != null && req.enterpriseId() > 0L) {
            return req.enterpriseId();
        }
        if (localUser == null) {
            return null;
        }
        Integer er = localUser.getEnterpriseRole();
        Long entId = localUser.getEnterpriseId();
        boolean enterpriseAdmin = er != null && er == 1;
        if (entId == null || entId <= 0L) {
            return null;
        }
        if (!enterpriseAdmin) {
            // 不是企业管理员：再看是不是 owner（owner 也可能 er=0）
            Long ownerId = orgWriteMapper.selectEnterpriseOwner(entId);
            if (ownerId == null || !ownerId.equals(localUser.getId())) {
                return null;   // 普通成员自购 → 个人额度
            }
        }
        // 企业必须 active，否则不往池里灌（停用/驳回企业不给扩容）
        String status = enterpriseStatusMapper.selectStatus(entId);
        if (!"active".equals(status)) {
            log.info("[portal] 企业不可用(status={})，本笔按个人额度处理 sellEnterpriseId={}",
                    status, entId);
            return null;
        }
        return entId;
    }
}