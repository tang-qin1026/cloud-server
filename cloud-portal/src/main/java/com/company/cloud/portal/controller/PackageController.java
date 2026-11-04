package com.company.cloud.portal.controller;

import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.audit.AuditActions;
import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.auth.entity.PortalUserMap;
import com.company.cloud.auth.repository.PortalUserMapRepository;
import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalOrderClient;
import com.company.cloud.portal.entity.PortalProductSku;
import com.company.cloud.portal.mapper.PortalProductSkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 套餐增值页（用户端）—— 查门户上的云存储套餐，并告知当前用户能不能下单。
 *
 * <pre>
 *  GET /api/billing/packages     在售套餐清单 + 我能否下单
 * </pre>
 *
 * <h3>套餐从哪来</h3>
 * 就是门户商城里的那批 SKU：产品侧通过 {@code GET /internal/products} 把 SKU（含
 * {@code quota_gb}、{@code price_cents}、{@code period_hours}）报给门户，
 * 门户 upsert 到自己的 {@code p_product}。本接口读的 {@code portal_product_sku}
 * 是同一份目录的本地镜像 —— 所以页面上显示的套餐、价格、规格与门户一致。
 *
 * <h3>谁能下单（2026-11-02 口径）</h3>
 * <table>
 *   <tr><th>身份</th><th>能下单</th><th>额度去向</th></tr>
 *   <tr><td>企业管理员 / owner</td><td>✅</td><td>进企业池，由他分给成员</td></tr>
 *   <tr><td>个人域用户</td><td>✅</td><td>进自己的个人额度</td></tr>
 *   <tr><td><b>企业内普通成员</b></td><td>❌ <b>只能预览</b></td><td>—</td></tr>
 * </table>
 *
 * <p>企业成员下单会被 {@code POST /orders} 直接拒绝（40301）。本接口把
 * {@code canPurchase=false} 与原因一并返回，前端据此**置灰购买按钮**，
 * 避免用户点了才被拒。
 */
@Slf4j
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class PackageController {

    private final com.company.cloud.portal.config.PortalProperties props;
    private final PortalProductSkuMapper skuMapper;
    /** 续费要进审计日志（2026-11-03）。 */
    private final AuditService auditService;
    private final PortalOrderClient orderClient;
    private final PortalUserMapRepository portalUserMapRepository;
    private final com.company.cloud.auth.enterprise.EnterprisePoolService poolService;
    private final UserRepository userRepository;
    private final com.company.cloud.auth.org.mapper.OrgWriteMapper orgWriteMapper;

    /** 在售套餐清单 + 当前用户的购买资格。 */
    @GetMapping("/packages")
    public Result<Map<String, Object>> packages(Authentication authentication) {
        CurrentUser cu = currentUser(authentication);

        boolean canPurchase = canPurchase(cu);
        String denyReason = canPurchase ? null : "企业成员只能查看套餐，如需增购请联系企业管理员";

        List<Map<String, Object>> list = new ArrayList<>();
        for (PortalProductSku s : skuMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PortalProductSku>()
                        .eq("is_active", true)
                        .orderByAsc("sort_no").orderByAsc("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", s.getSkuId());
            m.put("name", s.getName());
            m.put("quotaGb", s.getQuotaGb());
            m.put("quotaBytes", s.getQuotaGb() == null ? null
                    : (long) s.getQuotaGb() * 1024L * 1024 * 1024);
            m.put("priceCents", s.getPriceCents());
            // 元：前端直接展示，不用再除 100
            m.put("priceYuan", s.getPriceCents() == null ? null
                    : BigDecimal.valueOf(s.getPriceCents()).movePointLeft(2)
                            .setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            m.put("periodHours", s.getPeriodHours());
            m.put("periodDays", s.getPeriodHours() == null ? null : s.getPeriodHours() / 24);
            m.put("stock", s.getStock());           // null = 不限库存
            m.put("spec", s.getSpec());             // 原始规格 JSON，前端可原样展示
            m.put("canPurchase", canPurchase);
            if (!canPurchase) {
                m.put("denyReason", denyReason);
            }
            list.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("canPurchase", canPurchase);
        out.put("denyReason", denyReason);
        out.put("buyerType", buyerType(cu));
        out.put("list", list);
        return Result.ok(out);
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 当前用户属于哪种购买方，前端可据此展示不同的说明文案。
     *
     * <ul>
     *   <li>{@code enterprise} —— 企业管理员/owner：买到的空间进企业池</li>
     *   <li>{@code personal}   —— 个人域用户：进自己额度</li>
     *   <li>{@code member}     —— 企业内普通成员：只读</li>
     * </ul>
     */
    private String buyerType(CurrentUser cu) {
        if (cu.isAdmin()) {
            return "platform";
        }
        Long entId = cu.getEnterpriseId();
        if (entId == null || entId <= 0L) {
            return "personal";
        }
        return canPurchase(cu) ? "enterprise" : "member";
    }

    /**
     * 与 {@code OrderController.assertCanPurchase} **同一套规则**：
     * 企业管理员（er=1）或 owner 才算企业购买方；个人域放行；企业普通成员拒绝。
     */
    private boolean canPurchase(CurrentUser cu) {
        if (cu.isAdmin()) {
            return true;
        }
        Long entId = cu.getEnterpriseId();
        if (entId == null || entId <= 0L) {
            return true;   // 个人域
        }
        Integer er = cu.getEnterpriseRole();
        if (er != null && er == 1) {
            return true;   // 企业管理员
        }
        Long ownerId = orgWriteMapper.selectEnterpriseOwner(entId);
        return ownerId != null && ownerId.equals(cu.getId());
    }

    private static CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
    /**
     * 续费某笔套餐 —— **延长原有开通一个周期，不新增容量**。
     *
     * <h3>与"新买"的区别（2026-11-02 口径）</h3>
     * <ul>
     *   <li>新买（{@code POST /orders}）→ 新增一笔 grant，容量累加</li>
     *   <li><b>本接口 → 只把该笔的到期时间往后推一个周期（默认 30 天），容量不变</b></li>
     * </ul>
     *
     * <h3>一次请求做三步</h3>
     * <ol>
     *   <li>校验：这笔开通属于本企业、未回收（已回收的不给续，否则等于白送空间）</li>
     *   <li>付钱：服务端调门户下单扣款（幂等键由本侧生成，同日重复点击只成功一次）</li>
     *   <li>延长：扣款成功后把 grant + provision 的 expire_at 各推一个周期，并落续费记录</li>
     * </ol>
     *
     * <p>门户随后会回调 {@code /internal/provision} 送一笔新 order_item —— 我方按
     * {@code subscription_renewal} 记录识别出这是续费单并跳过发放，容量不会被重复叠加。
     */
    @org.springframework.web.bind.annotation.PostMapping("/subscriptions/{orderItemId}/renew")
    public Result<Map<String, Object>> renew(Authentication authentication,
                                             @org.springframework.web.bind.annotation.PathVariable long orderItemId) {
        CurrentUser cu = currentUser(authentication);
        Long entId = cu.getEnterpriseId();

        // 个人域（普通用户）：续费**自己购买的**套餐（2026-11-04 放开）
        if (entId == null || entId <= 0L) {
            return renewPersonal(cu, orderItemId);
        }
        // 企业内：续费=花企业的钱，仍只允许管理员（普通成员只能看）
        if (!canPurchase(cu)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "企业成员只能查看套餐，如需续费请联系企业管理员");
        }
        String portalUserId = portalUserMapRepository.findByLocalUserId(cu.getId())
                .map(PortalUserMap::getPortalUserId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "当前账号未关联门户用户，无法续费（请用门户账号登录后重试）"));

        // 先查出这笔的 skuId/数量/单周期，用来自动下单
        Map<String, Object> one = poolService.subscriptions(entId).stream()
                .filter(r -> String.valueOf(orderItemId).equals(String.valueOf(r.get("orderItemId"))))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "找不到该套餐：" + orderItemId));
        if (Boolean.TRUE.equals(one.get("expired")) && "reclaimed".equals(String.valueOf(one.get("status")))) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该套餐已到期回收，无法续费；请直接购买新套餐");
        }
        String skuId = String.valueOf(one.get("skuId"));
        int quantity = ((Number) one.get("quantity")).intValue();
        if (skuId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该套餐查不到对应的商品，无法续费");
        }

        long priceCents = ((Number) one.get("totalPriceCents")).longValue();

        // 两阶段记账 · 第一阶段：先记账（pending）+ 秒级节流，再扣款。
        // 幂等键由服务端生成（每次续费独立一笔），或复用"未完成"那笔的 key。
        Map<String, Object> begun;
        try {
            begun = poolService.beginRenewal(entId, orderItemId, priceCents);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        String key = String.valueOf(begun.get("clientOrderKey"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("product_code", props.getProductCode(), "sku_id", skuId, "quantity", quantity));

        PortalApiResponse resp = orderClient.createOrder(portalUserId, key, items, null);
        // success()：code=0 正常，code=3003 幂等重复（语义等价于成功，本次没扣款）
        if (!resp.success()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "门户下单失败（code=" + resp.code() + "）：" + resp.message());
        }
        String portalOrderId = resp.data() == null ? null : resp.data().path("order_id").asText(null);

        Map<String, Object> out;
        try {
            out = poolService.applyRenewal(entId, orderItemId, key, portalOrderId, priceCents);
        } catch (Exception e) {
            // 走到这里说明**门户已经扣款成功**，但本地延长失败。
            // 不能静默吞掉：把幂等键和门户订单号打出来，前端也提示可安全重试。
            log.error("[renew] 已扣款但延长失败 ent={} orderItem={} key={} portalOrder={} err={}",
                    entId, orderItemId, key, portalOrderId, e.toString());
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "已扣款但续费写入失败，请**重新点击续费**（同一幂等键会跳过重复扣款），"
                            + "若仍失败请联系管理员并提供订单号 " + portalOrderId
                            + " / 幂等键 " + key);
        }
        out.put("portalOrderId", portalOrderId);
        out.put("priceCents", priceCents);
        out.put("priceYuan", BigDecimal.valueOf(priceCents).movePointLeft(2).toPlainString());
        out.put("pool", poolService.pool(entId));

        // 续费进审计日志（2026-11-03）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderItemId", orderItemId);
        detail.put("skuId", skuId);
        detail.put("quantity", quantity);
        detail.put("extendHours", out.get("extendHours"));
        detail.put("extendDays", out.get("extendDays"));
        detail.put("expireAtBefore", String.valueOf(out.get("expireAtBefore")));
        detail.put("expireAtAfter", String.valueOf(out.get("expireAtAfter")));
        detail.put("priceCents", priceCents);
        detail.put("clientOrderKey", key);
        detail.put("portalOrderId", portalOrderId);
        detail.put("duplicated", Boolean.TRUE.equals(out.get("duplicated")));
        auditService.record(new AuditEvent(
                cu.getId(), AuditActions.BILLING_RENEW, String.valueOf(orderItemId), null, detail));

        log.info("[renew] 企业 {} 续费 orderItem={} sku={} x{} 到期 {} → {} 门户单={}",
                entId, orderItemId, skuId, quantity,
                out.get("expireAtBefore"), out.get("expireAtAfter"), portalOrderId);
        return Result.ok(out);
    }
    // ── 个人域（普通用户）续费 2026-11-04 ─────────────────────────

    /**
     * 个人域用户的套餐列表：自己买过的那几笔（含到期时间与续费次数）。
     *
     * <p>企业用户走 {@code GET /enterprise/subscriptions}（企业池口径），个人用户走这里。
     */
    @GetMapping("/subscriptions")
    public Result<java.util.List<Map<String, Object>>> subscriptions(Authentication authentication) {
        CurrentUser cu = currentUser(authentication);
        Long entId = cu.getEnterpriseId();
        if (entId != null && entId > 0L) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "企业用户请使用 GET /enterprise/subscriptions（企业池口径）");
        }
        return Result.ok(poolService.personalSubscriptions(cu.getId()));
    }

    /**
     * 个人域续费：只延长自己那笔套餐的到期时间，**不增加容量**。
     *
     * <p>流程与企业版一致（两阶段记账 + 秒级节流 + 未完成记录复用），
     * 差别只在数据来源：{@code portal_provision} 里该用户名下的记录。
     */
    private Result<Map<String, Object>> renewPersonal(CurrentUser cu, long orderItemId) {
        String portalUserId = portalUserMapRepository.findByLocalUserId(cu.getId())
                .map(PortalUserMap::getPortalUserId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "当前账号未关联门户用户，无法续费（请用门户账号登录后重试）"));

        Map<String, Object> one = poolService.personalSubscriptions(cu.getId()).stream()
                .filter(r -> String.valueOf(orderItemId).equals(String.valueOf(r.get("orderItemId"))))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "找不到该套餐：" + orderItemId + "（只能续费自己购买的套餐）"));
        if ("failed".equals(String.valueOf(one.get("status")))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该套餐开通失败，无法续费；请重新购买");
        }
        String skuId = String.valueOf(one.get("skuId"));
        int quantity = ((Number) one.get("quantity")).intValue();
        long priceCents = ((Number) one.get("priceCents")).longValue() * quantity;

        Map<String, Object> begun;
        try {
            begun = poolService.beginPersonalRenewal(cu.getId(), orderItemId, priceCents);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        String key = String.valueOf(begun.get("clientOrderKey"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("product_code", props.getProductCode(), "sku_id", skuId, "quantity", quantity));

        PortalApiResponse resp = orderClient.createOrder(portalUserId, key, items, null);
        if (!resp.success()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "门户下单失败（code=" + resp.code() + "）：" + resp.message());
        }
        String portalOrderId = resp.data() == null ? null : resp.data().path("order_id").asText(null);

        Map<String, Object> out;
        try {
            out = poolService.applyPersonalRenewal(cu.getId(), orderItemId, key, portalOrderId);
        } catch (Exception e) {
            log.error("[renew] 个人域已扣款但延长失败 userId={} item={} key={} portalOrder={} err={}",
                    cu.getId(), orderItemId, key, portalOrderId, e.toString());
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "已扣款但续费写入失败，请**重新点击续费**（同一幂等键会跳过重复扣款），"
                            + "若仍失败请联系管理员并提供订单号 " + portalOrderId + " / 幂等键 " + key);
        }

        out.put("portalOrderId", portalOrderId);
        out.put("skuId", skuId);
        out.put("quantity", quantity);
        out.put("priceCents", priceCents);
        out.put("priceYuan", BigDecimal.valueOf(priceCents).movePointLeft(2).toPlainString());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderItemId", orderItemId);
        detail.put("skuId", skuId);
        detail.put("quantity", quantity);
        detail.put("priceCents", priceCents);
        detail.put("clientOrderKey", key);
        detail.put("portalOrderId", portalOrderId);
        detail.put("expireAtBefore", begun.get("expireAtBefore"));
        detail.put("expireAtAfter", out.get("expireAtAfter"));
        detail.put("extendHours", out.get("extendHours"));
        detail.put("duplicated", out.get("duplicated"));
        detail.put("buyer", "personal");
        auditService.record(new AuditEvent(cu.getId(), AuditActions.BILLING_RENEW,
                String.valueOf(orderItemId), null, detail));

        log.info("[renew] 个人域续费成功 userId={} item={} 到期={} 金额={}分",
                cu.getId(), orderItemId, out.get("expireAtAfter"), priceCents);
        return Result.ok(out);
    }
}