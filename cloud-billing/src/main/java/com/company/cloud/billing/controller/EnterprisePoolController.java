package com.company.cloud.billing.controller;

import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.auth.enterprise.EnterprisePoolService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业资源分配（docs/31 §8）。
 *
 * <pre>
 *  GET  /enterprise/pool                       池概览（成员以上都能看）
 *  GET  /enterprise/allocations                成员分配清单（普通成员只看自己）
 *  PUT  /enterprise/allocations/{localUserId}  设置某成员额度（企业管理员）
 * </pre>
 *
 * <h3>权限口径</h3>
 * <ul>
 *   <li>概览 / 清单：企业内**任何成员**可看（普通成员只能看到自己那一行）</li>
 *   <li>分配：需要 {@code org.member.manage}（企业管理员自带 11 项里有）</li>
 * </ul>
 *
 * <p>个人域用户（{@code enterprise_id = 0}）调用这三个接口一律 40000 ——
 * 他没有企业池，不是"没权限"，而是"不适用"。
 */
@Slf4j
@RestController
@RequestMapping("/enterprise")
@RequiredArgsConstructor
public class EnterprisePoolController {

    private final EnterprisePoolService poolService;
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;

    @GetMapping("/pool")
    public Result<Map<String, Object>> pool(Authentication authentication) {
        CurrentUser cu = requireEnterpriseUser(authentication);
        return Result.ok(poolService.pool(cu.getEnterpriseId()));
    }

    /**
     * 每一笔套餐（企业买过什么、什么时候到期、还剩多少）。
     *
     * <p>企业管理员据此看「我买了哪些套餐、哪笔快到期了、要不要续费」。
     * 按到期时间倒序（最近的在前）。
     */
    @GetMapping("/subscriptions")
    public Result<Map<String, Object>> subscriptions(Authentication authentication) {
        CurrentUser cu = requireEnterpriseUser(authentication);
        permissionChecker.require(cu, "org.member.read");
        List<Map<String, Object>> rows = poolService.subscriptions(cu.getEnterpriseId());
        return Result.ok(Map.of("total", rows.size(), "list", rows));
    }

    // 续费端点**不在这里**：续费要调门户下单（PortalOrderClient 在 cloud-portal，
    // 而 cloud-billing 不能依赖 cloud-portal）。见 cloud-portal 的 PackageController：
    //   POST /api/billing/subscriptions/{orderItemId}/renew
    @GetMapping("/allocations")
    public Result<Map<String, Object>> allocations(Authentication authentication) {
        CurrentUser cu = requireEnterpriseUser(authentication);
        List<Map<String, Object>> all = poolService.allocations(cu.getEnterpriseId());
        boolean admin = cu.isEnterpriseAdmin() || cu.isAdmin();
        List<Map<String, Object>> list = admin
                ? all
                : all.stream()
                        .filter(r -> String.valueOf(r.get("localUserId")).equals(String.valueOf(cu.getId())))
                        .toList();
        return Result.ok(Map.of("total", list.size(), "list", list));
    }

    @PutMapping("/allocations/{localUserId}")
    public Result<Map<String, Object>> allocate(Authentication authentication,
                                                @PathVariable long localUserId,
                                                @RequestBody AllocateRequest req) {
        CurrentUser cu = requireEnterpriseUser(authentication);
        permissionChecker.require(cu, "org.member.manage");
        if (req == null || req.bytes() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "bytes 必填（0 表示收回）");
        }
        if (req.bytes() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "bytes 不能为负");
        }
        try {
            poolService.allocate(cu.getEnterpriseId(), localUserId, req.bytes());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("localUserId", localUserId);
        out.put("allocatedBytes", req.bytes());
        out.put("pool", poolService.pool(cu.getEnterpriseId()));
        log.info("[pool] 分配变更 enterpriseId={} targetUserId={} bytes={} by={}",
                cu.getEnterpriseId(), localUserId, req.bytes(), cu.getId());
        return Result.ok(out);
    }

    // ── 内部 ─────────────────────────────────────────────────

    private static CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    /** 企业上下文必须是有效企业（个人域 = 40000 不适用）。 */
    private static CurrentUser requireEnterpriseUser(Authentication authentication) {
        CurrentUser cu = currentUser(authentication);
        if (cu.getEnterpriseId() == null || cu.getEnterpriseId() <= 0L) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该接口只在企业内可用（个人域账号没有企业池）");
        }
        return cu;
    }

    /** 设置额度入参。{@code bytes} 为字节数，0 表示收回。 */
    public record AllocateRequest(Long bytes) {
    }
}
