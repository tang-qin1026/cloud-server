package com.company.cloud.portal.controller;

import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.security.CurrentUser;
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
        if (entId == null || entId <= 0L) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该接口只在企业内可用（个人域没有套餐开通记录）");
        }
        // 续费=花钱，只允许企业管理员（普通成员只能看，与套餐页口径一致）
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

        String key = "rnw-" + entId + "-" + orderItemId + "-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("product_code", props.getProductCode(), "sku_id", skuId, "quantity", quantity));

        PortalApiResponse resp = orderClient.createOrder(portalUserId, key, items, null);
        // success()：code=0 正常，code=3003 幂等重复（语义等价于成功，本次没扣款）
        if (!resp.success()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "门户下单失败（code=" + resp.code() + "）：" + resp.message());
        }
        String portalOrderId = resp.data() == null ? null : resp.data().path("order_id").asText(null);
        long priceCents = ((Number) one.get("totalPriceCents")).longValue();

        Map<String, Object> out = poolService.applyRenewal(entId, orderItemId, key, portalOrderId, priceCents);
        out.put("portalOrderId", portalOrderId);
        out.put("priceCents", priceCents);
        out.put("priceYuan", BigDecimal.valueOf(priceCents).movePointLeft(2).toPlainString());
        out.put("pool", poolService.pool(entId));
        log.info("[renew] 企业 {} 续费 orderItem={} sku={} x{} 到期 {} → {} 门户单={}",
                entId, orderItemId, skuId, quantity,
                out.get("expireAtBefore"), out.get("expireAtAfter"), portalOrderId);
        return Result.ok(out);
    }
}