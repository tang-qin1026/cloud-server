package com.company.cloud.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.billing.dto.*;
import com.company.cloud.billing.entity.BillingConfig;
import com.company.cloud.billing.entity.IncreaseRequest;
import com.company.cloud.billing.mapper.BillingConfigMapper;
import com.company.cloud.billing.mapper.IncreaseRequestMapper;
import com.company.cloud.billing.mapper.UserQuotaMapper;
import com.company.cloud.common.audit.AuditActions;
import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 用户端计费服务（§4.1）：配置查询、额度查询、提交增额申请、我的申请列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingConfigMapper configMapper;
    private final IncreaseRequestMapper requestMapper;
    private final UserQuotaMapper userQuotaMapper;
    private final AuditService auditService;

    /** 计费配置常量说明（§4.2 PATCH 影响范围提示） */
    private static final String CONFIG_NOTE = "freeBytes 仅影响此后新注册用户（R3 快照制）";

    /**
     * 读取计费配置（§4.1 GET /billing/config）。单行 id=1。
     */
    public ConfigView getConfig() {
        BillingConfig cfg = configMapper.selectById(1);
        if (cfg == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "计费配置缺失");
        }
        return new ConfigView(
                cfg.getFreeBytes() == null ? 0L : cfg.getFreeBytes(),
                cfg.getPricePerGbMonthCents() == null ? 0 : cfg.getPricePerGbMonthCents(),
                CONFIG_NOTE);
    }

    /**
     * 当前用户额度视图（§4.1 GET /billing/quota）。
     * uploadBlocked = usedBytes > freeBytes + extraBytes（R7 推导，不落库）。
     */
    public QuotaView getQuota(CurrentUser cu) {
        UserQuotaView quota = userQuotaMapper.selectByUserId(cu.getId());
        if (quota == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        long used = quota.usedBytes() == null ? 0L : quota.usedBytes();
        long extra = quota.extraBytes() == null ? 0L : quota.extraBytes();
        long free = quota.freeBytes() == null ? 0L : quota.freeBytes();
        // docs/31：企业管理员分配给该成员的额度也算进可用空间
        long enterprise = quota.enterpriseAllocatedBytes() == null ? 0L : quota.enterpriseAllocatedBytes();
        boolean blocked = used > free + extra + enterprise;
        return new QuotaView(free, extra, quota.extraExpireAt(), used, blocked, free, enterprise);
    }

    /**
     * 提交增额申请（§4.1 POST /billing/increase-requests）。
     * 金额后端重算 = gbCount × price_per_gb_month_cents；忽略前端金额。
     * 同一用户存在 pending 申请允许再提交（叠加制）。
     */
    @Transactional
    public CreateResult createIncreaseRequest(CurrentUser cu, CreateIncreaseRequest req) {
        if (req.gbCount() == null || req.gbCount() < 1 || req.gbCount() > 1000) {
            throw new BizException(ErrorCode.GB_COUNT_INVALID);
        }
        BillingConfig cfg = configMapper.selectById(1);
        if (cfg == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "计费配置缺失");
        }
        int gb = req.gbCount();
        long amountCents = (long) gb * cfg.getPricePerGbMonthCents();

        IncreaseRequest ir = new IncreaseRequest();
        ir.setUserId(cu.getId());
        ir.setGbCount(gb);
        ir.setAmountCents(amountCents);
        ir.setStatus("pending");
        ir.setRemark(req.remark());
        requestMapper.insert(ir);

        // 审计（§4.6 billing_request）
        auditService.record(new AuditEvent(
                cu.getId(), AuditActions.BILLING_REQUEST, String.valueOf(ir.getId()), null,
                Map.of("gbCount", gb, "amountCents", amountCents)));

        return new CreateResult(ir.getId(), amountCents, "pending");
    }

    /**
     * 我的申请列表（§4.1 GET /billing/increase-requests），按提交时间倒序。
     */
    public Page<RequestView> listMyRequests(CurrentUser cu, int page, int size) {
        QueryWrapper<IncreaseRequest> qw = new QueryWrapper<IncreaseRequest>()
                .eq("user_id", cu.getId())
                .orderByDesc("created_at");
        Page<IncreaseRequest> p = requestMapper.selectPage(
                Page.of(Math.max(1, page), size), qw);
        Page<RequestView> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream()
                .map(this::toRequestView)
                .toList());
        return out;
    }

    private RequestView toRequestView(IncreaseRequest r) {
        return new RequestView(
                r.getId(), r.getUserId(),
                userQuotaMapper.selectUsername(r.getUserId()),
                r.getGbCount(), r.getAmountCents(), r.getStatus(), r.getRemark(),
                r.getCreatedAt(), r.getHandledAt());
    }

    /** 提交申请结果（§4.1）：{ id, amountCents, status } */
    public record CreateResult(Long id, Long amountCents, String status) {
    }
}