package com.company.cloud.billing.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.billing.dto.ConfigView;
import com.company.cloud.billing.dto.CreateIncreaseRequest;
import com.company.cloud.billing.dto.QuotaView;
import com.company.cloud.billing.dto.RequestView;
import com.company.cloud.billing.service.BillingService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端计费接口（§4.1，登录用户即可访问，A 组 JWT 鉴权）：
 *  - GET   /billing/config                 计费配置
 *  - GET   /billing/quota                  我的额度
 *  - POST  /billing/increase-requests      提交增额申请
 *  - GET   /billing/increase-requests      我的申请列表
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    /** 权限点校验：权限配置必须真实生效。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;
    private final BillingService billingService;

    @GetMapping("/config")
    public Result<ConfigView> config(Authentication authentication) {
        permissionChecker.require(currentUser(authentication), "billing.read");
        return Result.ok(billingService.getConfig());
    }

    @GetMapping("/quota")
    public Result<QuotaView> quota(Authentication authentication) {
        permissionChecker.require(currentUser(authentication), "quota.read");
        return Result.ok(billingService.getQuota(currentUser(authentication)));
    }

    @PostMapping("/increase-requests")
    public Result<BillingService.CreateResult> createRequest(
            Authentication authentication,
            @Valid @RequestBody CreateIncreaseRequest req) {
        permissionChecker.require(currentUser(authentication), "increase.apply");
        return Result.ok(billingService.createIncreaseRequest(currentUser(authentication), req));
    }

    @GetMapping("/increase-requests")
    public Result<Page<RequestView>> myRequests(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        permissionChecker.require(currentUser(authentication), "increase.read");
        return Result.ok(billingService.listMyRequests(currentUser(authentication), page, size));
    }

    private CurrentUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }
}