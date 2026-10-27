package com.company.cloud.common.audit;

import java.util.Map;

/**
 * 审计事件。动作取值见 {@link AuditActions}。
 *
 * @param userId       操作者 ID（系统任务可为 null）
 * @param action       动作，见 AuditActions 动作字典
 * @param target       操作对象（文件ID、用户名等），可为 null
 * @param ip           来源 IP，可为 null
 * @param detail       扩展信息（文件名、大小、前后值等），落库为 JSONB
 * @param enterpriseId 所属企业（V2025 / M2 新增）。{@code null} = 由调用线程从
 *                     {@code TenantContext} 补齐（**异步线程里 ThreadLocal 是空的**）
 */
public record AuditEvent(Long userId, String action, String target, String ip,
                         Map<String, Object> detail, Long enterpriseId) {

    /**
     * 兼容构造器（V2025 之前的 5 参写法）。
     *
     * <p>保留它是为了让既有 21 个调用点**零改动**继续编译；
     * 企业 id 由 {@code AuditServiceImpl} 在<b>调用线程</b>补齐。
     */
    public AuditEvent(Long userId, String action, String target, String ip,
                      Map<String, Object> detail) {
        this(userId, action, target, ip, detail, null);
    }
}
