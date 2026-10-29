package com.company.cloud.portal.controller;

import com.company.cloud.auth.entity.PortalUserMap;
import com.company.cloud.auth.repository.PortalUserMapRepository;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalOrderClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 服务端下单（docs/30）—— 产品内购买，替用户下单并支付。
 *
 * <pre>
 *  POST /api/orders               下单并支付（幂等，clientOrderKey 为幂等键）
 *  GET  /api/orders/{orderId}     查开通结果（provision_done=true 即终态）
 * </pre>
 *
 * <h3>为什么要有这两层</h3>
 * 门户的 {@code /api/internal/orders} 用**产品服务凭据**鉴权，它只知道"哪个产品在调"，
 * 不知道背后是哪个用户。所以：
 * <ul>
 *   <li>{@code portal_user_id} 必须由本侧**从登录态映射**得到
 *       （{@code portal_user_map}），**绝不能接受前端传入** ——
 *       否则任何人都能替别人下单、扣别人的钱</li>
 *   <li>单笔/日累计上限、商品归属等护栏在门户侧，本侧不重复实现</li>
 * </ul>
 *
 * <h3>幂等</h3>
 * {@code clientOrderKey} 必填：同一个业务动作重试必须复用同一个 key。
 * 门户侧 {@code o_order.client_order_key} 有唯一索引，重放返回 {@code duplicated=true}（视为成功）。
 * 本侧不做二次幂等——多一层只会让"重试到底该不该换 key"更难判断。
 */
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    /** 幂等键格式（与门户一致）：8~64 位，字母/数字/._:- */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

    /** 门户"订单不存在"（含"订单里没有你的商品"，为防探测而统一）。 */
    private static final int PORTAL_ORDER_NOT_FOUND = 4001;

    private final PortalOrderClient orderClient;
    private final PortalUserMapRepository portalUserMapRepository;
    private final com.company.cloud.portal.config.PortalProperties props;

    /** 下单并支付。 */
    @PostMapping
    public Result<JsonNode> create(Authentication authentication, @RequestBody CreateOrderRequest req) {
        CurrentUser cu = currentUser(authentication);
        String portalUserId = resolvePortalUserId(cu);

        String key = req.clientOrderKey() == null ? "" : req.clientOrderKey().trim();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "clientOrderKey 必填且需 8~64 位（字母/数字/._:-）；同一业务动作重试必须复用同一个 key");
        }
        if (req.skuId() == null || req.skuId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "skuId 不能为空");
        }
        int quantity = req.quantity() == null ? 1 : req.quantity();
        if (quantity < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "quantity 必须是正整数");
        }

        // 商品归属由门户校验（product_code 必须等于本产品 code），这里照实带上
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("product_code", props.getProductCode());
        item.put("sku_id", req.skuId().trim());
        item.put("quantity", quantity);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item);

        PortalApiResponse r = orderClient.createOrder(portalUserId, key, items, req.consentRef());
        if (r.success()) {
            JsonNode d = r.data();
            if (d != null && d.path("duplicated").asBoolean(false)) {
                log.info("[order] 幂等重放 productUserId={} key={} orderId={}",
                        cu.getId(), key, d.path("order_id").asText(null));
            } else {
                log.info("[order] 下单成功 productUserId={} key={} orderId={} total={}",
                        cu.getId(), key, d.path("order_id").asText(null), d.path("total_cents").asLong(0));
            }
        }
        return Result.ok(unwrap(r, "下单"));
    }

    /** 查开通结果。 */
    @GetMapping("/{orderId}")
    public Result<JsonNode> get(Authentication authentication, @PathVariable String orderId) {
        currentUser(authentication);
        if (orderId == null || orderId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "orderId 不能为空");
        }
        return Result.ok(unwrap(orderClient.getOrder(orderId.trim()), "查开通结果"));
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * 登录态 → 门户用户 id。
     *
     * <p>只在 {@code portal_user_map} 里有映射的账号才能下单（即门户用户）。
     * 纯本地账号（如早期的 {@code admin}）没有门户归属，无法扣款，直接拒绝。
     */
    private String resolvePortalUserId(CurrentUser cu) {
        return portalUserMapRepository.findByLocalUserId(cu.getId())
                .map(PortalUserMap::getPortalUserId)
                .filter(s -> s != null && !s.isBlank())
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "当前账号未关联门户用户，无法下单（请用门户账号登录后重试）"));
    }

    private static CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    /**
     * 门户业务码 → 本侧错误。
     *
     * <p>核心原则：**不要重试的码要如实告诉前端**（1004 被拒 / 3001 余额不足 / 4001 订单不存在），
     * 否则用户会看到"系统繁忙"却不知道是自己没钱。
     */
    private static JsonNode unwrap(PortalApiResponse r, String what) {
        if (r.success()) {
            return r.data();
        }
        String msg = r.message() == null ? "" : r.message();
        switch (r.code()) {
            case 1001 -> throw new BizException(ErrorCode.BAD_REQUEST, "参数非法：" + msg);
            case 1002, 1003 -> {
                // 1002 签名/重放不符；1003 凭据过期 —— 都是本侧签名实现问题
                log.error("[order] {} 失败：服务凭据问题 code={} {}", what, r.code(), msg);
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "内部调用失败（凭据无效或过期，请联系运维）");
            }
            case 1004 -> throw new BizException(ErrorCode.BAD_REQUEST, "下单被拒绝：" + msg);
            // 门户这两个码的 message 往往与我们的话术重复（"余额不足" / "钱包冻结"），
            // 直接用合并版，避免出现"余额不足，请先充值：余额不足"
            case 3001 -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "钱包余额不足，请先充值（门户不返回具体余额）");
            case 3002 -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "钱包已冻结，请联系门户客服");
            case PORTAL_ORDER_NOT_FOUND -> throw new BizException(ErrorCode.BAD_REQUEST, "订单不存在");
            case 4002 -> throw new BizException(ErrorCode.BAD_REQUEST, "订单状态不允许该操作：" + msg);
            case 4003, 4004 -> throw new BizException(ErrorCode.BAD_REQUEST, "商品不可售：" + msg);
            default -> {
                log.warn("[order] {} 失败 code={} msg={}", what, r.code(), msg);
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "门户返回 " + r.code() + "：" + msg);
            }
        }
    }

    /**
     * 下单入参。
     *
     * @param skuId          商品 id（从 {@code GET /internal/products} 拿到的 sku_id）
     * @param quantity       数量，默认 1
     * @param clientOrderKey **必填**，幂等键；同一业务动作重试必须复用
     * @param consentRef     用户点"购买"时产生的确认号（可空，强烈建议传）
     */
    public record CreateOrderRequest(String skuId, Integer quantity,
                                     String clientOrderKey, String consentRef) {
    }
}
