package com.company.cloud.portal.controller;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import com.company.cloud.portal.client.PortalApiResponse;
import com.company.cloud.portal.client.PortalDirectoryClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业目录（平台管理员专用，docs/29）。
 *
 * <pre>
 *  GET /api/admin/enterprises                     企业清单（仅元数据）
 *  GET /api/admin/enterprises/{id}/accounts       某家企业的成员
 * </pre>
 *
 * <h3>为什么这条路由必须自己校验 {@code role === 'admin'}</h3>
 * 门户的 {@code /api/internal/enterprises} 用**服务凭据**鉴权 —— 它只知道
 * "是哪个产品在调"，**不知道背后是哪个用户**。也就是说：任何持有本产品私钥的
 * 进程都能拉到这张表。所以"仅平台管理员可见"是**本侧**的职责，门户帮不了。
 *
 * <p>另外企业清单**不能**给企业管理员看：否则他能枚举出其他所有企业。
 *
 * <h3>为什么拆成两个接口</h3>
 * 合成一个 = 一次调用就能拉走全部企业的全部手机号。拆开之后至少要先选中一家。
 *
 * <h3>响应字段</h3>
 * 直接透传门户的格式（snake_case），与 docs/29 §4.2 一致：
 * {@code enterprise_id / code / name / status / member_count / expire_at / created_at}。
 * {@code enterprise_id} 是**字符串**（雪花 id 转 number 会丢精度）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/enterprises")
@RequiredArgsConstructor
public class AdminEnterpriseDirectoryController {

    /** 门户「企业不存在或未激活」业务码（仅下钻成员时出现）。 */
    private static final int PORTAL_ENTERPRISE_INACTIVE = 2007;

    /** 单页上限，与门户一致（超过会被门户静默夹断，这里先夹一次省一次往返）。 */
    private static final int MAX_LIMIT = 200;

    private final PortalDirectoryClient directoryClient;

    /** 企业清单。{@code status} 不传 = 全部状态。 */
    @GetMapping
    public Result<JsonNode> enterprises(Authentication authentication,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        requirePlatformAdmin(authentication);
        PortalApiResponse r = directoryClient.listEnterprises(
                status, keyword, clamp(limit), Math.max(offset, 0));
        return Result.ok(unwrap(r, "企业清单"));
    }

    /** 某家企业的成员。{@code id} 是门户的 {@code enterprise_id}（字符串）。 */
    @GetMapping("/{id}/accounts")
    public Result<JsonNode> accounts(Authentication authentication,
                                     @PathVariable("id") String id,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        requirePlatformAdmin(authentication);
        PortalApiResponse r = directoryClient.listEnterpriseAccounts(
                id, clamp(limit), Math.max(offset, 0));
        return Result.ok(unwrap(r, "企业成员"));
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 仅平台管理员（{@code role='admin'}）。企业管理员/员工一律拒绝。 */
    private static void requirePlatformAdmin(Authentication authentication) {
        CurrentUser cu = null;
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser c) {
            cu = c;
        }
        if (cu == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if (!cu.isAdmin() || cu.isEnterpriseAdmin()) {
            // 企业管理员即使 role 被误配成 admin 也要挡掉（企业管理员不该看到别家企业）
            throw new BizException(ErrorCode.FORBIDDEN, "企业列表仅平台管理员可见");
        }
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    /**
     * 门户响应 → 本侧结果。
     *
     * <p>业务码映射：
     * <ul>
     *   <li>{@code 0} → 透传 data</li>
     *   <li>{@code 2007} → 「该企业尚未激活」（下钻成员时）</li>
     *   <li>{@code 1002} → 服务凭据问题，属配置错误，需要运维介入</li>
     *   <li>其他 → 带门户码与原文抛出，便于前端提示与排查</li>
     * </ul>
     */
    private static JsonNode unwrap(PortalApiResponse r, String what) {
        if (r.success()) {
            return r.data();
        }
        if (r.code() == PORTAL_ENTERPRISE_INACTIVE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该企业尚未激活");
        }
        if (r.credentialFailure()) {
            log.error("[portal] {} 调用失败：服务凭据无效（{}）—— 检查公钥登记、kid、时钟",
                    what, r.describe());
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "内部调用失败（凭据无效，请联系运维）");
        }
        log.warn("[portal] {} 调用失败 code={} msg={}", what, r.code(), r.message());
        throw new BizException(ErrorCode.SYSTEM_ERROR,
                "门户返回 " + r.code() + "：" + (r.message() == null ? "" : r.message()));
    }
}
