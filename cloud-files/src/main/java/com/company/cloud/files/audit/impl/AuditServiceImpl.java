package com.company.cloud.files.audit.impl;

import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.tenant.TenantContext;
import com.company.cloud.files.audit.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * 审计落库实现（R-C08）：异步写 {@code audit_logs}，不阻塞调用方主流程。
 *
 * <p>降级原则：序列化 / 落库任何异常只 {@code log.error}，绝不向上抛出——
 * 审计不能拖垮主流程（契约：审计丢失只允许发生在极端故障场景）。
 *
 * <p><b>V2025（M2）关键改动：企业 id 必须在调用线程取好。</b>
 * 原实现用 {@code @Async("auditExecutor")} 直接标注 {@code record()}，
 * 但 <b>{@code TenantContext} 是 ThreadLocal，切换线程后必然为空</b> →
 * 所有审计会落成 {@code enterprise_id = 0}，租户审计从此不可用。
 *
 * <p>所以改为：{@code record()} <b>同步</b>执行（仅取企业 id），
 * 再把"落库"这一真正耗时的动作交给 {@code auditExecutor} 线程池。
 * 对外行为不变（仍然立即返回、仍然异步落库），但企业 id 不再丢。
 */
@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final Executor auditExecutor;

    public AuditServiceImpl(AuditLogMapper auditLogMapper,
                            ObjectMapper objectMapper,
                            @Qualifier("auditExecutor") Executor auditExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.auditExecutor = auditExecutor;
    }

    @Override
    public void record(AuditEvent event) {
        // ★ 在调用线程取企业 id（此时 TenantContext 还有效）
        Long captured = event.enterpriseId() != null ? event.enterpriseId() : TenantContext.idOrNull();
        final long enterpriseId = captured == null ? 0L : captured;

        try {
            auditExecutor.execute(() -> persist(event, enterpriseId));
        } catch (Exception e) {
            // 线程池拒绝等极端情况：降级为同步写，避免丢审计
            log.warn("[audit] 异步提交失败，改为同步落库: action={} err={}", event.action(), e.toString());
            persist(event, enterpriseId);
        }
    }

    private void persist(AuditEvent event, long enterpriseId) {
        try {
            String detail = event.detail() == null ? null : objectMapper.writeValueAsString(event.detail());
            auditLogMapper.insertEvent(event.userId(), event.action(), event.target(),
                    event.ip(), detail, enterpriseId);
        } catch (Exception e) {
            log.error("[audit] 审计落库失败，已降级丢弃: action={} target={} err={}",
                    event.action(), event.target(), e.toString());
        }
    }
}
