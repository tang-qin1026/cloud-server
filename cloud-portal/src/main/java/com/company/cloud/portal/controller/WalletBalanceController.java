package com.company.cloud.portal.controller;

import com.company.cloud.auth.entity.PortalUserMap;
import com.company.cloud.auth.repository.PortalUserMapRepository;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalWalletClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钱包余额查询（docs/32）—— 产品侧代理门户的 {@code GET /api/internal/wallet/balance}。
 *
 * <pre>
 *  GET /api/billing/wallet     查我自己的钱包余额
 * </pre>
 *
 * <h3>为什么要这个接口</h3>
 * 在这之前，产品只能从扣费的返回里（{@code 3001} + {@code data.balance}）知道余额不足 ——
 * **用户点了才发现钱不够**。有了它就能在付费页先看一眼：
 * <pre>
 * 余额充足 → 直接展示可购买
 * 余额不足 → "余额 12.34 元，还差 X 元，去充值"（不发起下单）
 * </pre>
 *
 * <h3>为什么放在 cloud-portal 而不是 cloud-billing</h3>
 * {@code PortalWalletClient} 在 cloud-portal，而 cloud-billing **不依赖** cloud-portal
 * （依赖方向是 cloud-billing → cloud-auth）。路径写成 {@code /billing/wallet}
 * 只是对前端友好，Spring 不关心它在哪个模块。
 *
 * <h3>三条语义，前端必须知道</h3>
 * <ol>
 *   <li><b>只读</b>：不会为用户创建钱包。{@code walletExists=false} 是正常状态
 *       （从未有过资金往来），**不是"用户不存在"**</li>
 *   <li><b>余额够 ≠ 扣款成功</b>：并发扣款、冻结、单笔限额都可能让扣费失败，
 *       最终以扣费返回码为准。本接口只适合"提前提示"</li>
 *   <li><b>别高频查</b>：余额只在充值和扣费时变，按小时级同步即可，
 *       不要每次页面刷新都打一次门户</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class WalletBalanceController {

    private final PortalWalletClient portalWalletClient;
    private final PortalUserMapRepository portalUserMapRepository;

    /** 查我自己的余额。 */
    @GetMapping("/wallet")
    public Result<Map<String, Object>> wallet(Authentication authentication) {
        CurrentUser cu = currentUser(authentication);

        // portal_user_id 必须由登录态映射得到，不接受前端传入 ——
        // 否则任何人都能查别人的余额（账户信息泄露）
        String portalUserId = portalUserMapRepository.findByLocalUserId(cu.getId())
                .map(PortalUserMap::getPortalUserId)
                .filter(s -> s != null && !s.isBlank())
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "当前账号未关联门户用户，查不到钱包余额（请用门户账号登录）"));

        PortalApiResponse r = portalWalletClient.getBalance(portalUserId);
        if (!r.success()) {
            // 1001 = 门户里查不到这个人；不能伪装成"余额 0"，否则会把"查错人"当成"没钱"
            if (r.code() == 1001) {
                throw new BizException(ErrorCode.BAD_REQUEST, "门户里查不到该用户");
            }
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "查询余额失败（门户返回 " + r.code() + "：" + r.message() + "）");
        }

        JsonNode d = r.data();
        long balanceCents = d == null ? 0L : d.path("balance_cents").asLong(0L);
        int status = d == null ? 0 : d.path("status").asInt(0);
        boolean walletExists = d != null && d.path("wallet_exists").asBoolean(false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balanceCents", balanceCents);
        // 元是给人看的：前端直接展示，不用再除 100
        out.put("balanceYuan", BigDecimal.valueOf(balanceCents).movePointLeft(2).toPlainString());
        out.put("status", status);
        out.put("frozen", status == 1);
        out.put("walletExists", walletExists);
        log.debug("[wallet] 查余额 userId={} balanceCents={} status={} exists={}",
                cu.getId(), balanceCents, status, walletExists);
        return Result.ok(out);
    }

    private static CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
}