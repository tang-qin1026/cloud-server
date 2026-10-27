package com.company.cloud.auth.tenant;

import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户隔离的**第三道防线**：服务层显式断言。
 *
 * <p>前两道（MyBatis 拦截器 / Hibernate {@code @TenantId}）都是**自动**的，
 * 但都有绕过的路径：手写 SQL、原生查询、按 id 直接 {@code findById}、
 * 以及 {@code files} 这类**还没有 enterprise_id 列**的表。
 * 本类负责在"按 id 拿到实体之后"再核一次归属。
 *
 * <p><b>为什么跨企业要按"不存在"返回</b>：返回 403 等于告诉攻击者"这个 id 存在但不是你的"，
 * 可以用来枚举 id；统一按 404/不存在处理则不会泄露存在性。
 *
 * <p><b>灰度语义</b>（{@code tenant.enforce}）：
 * <ul>
 *   <li>{@code false}：只打 WARN 日志，**不拒绝**（观察期，用来发现"本该拒绝"的调用点）</li>
 *   <li>{@code true}：抛 {@link BizException}</li>
 * </ul>
 *
 * <p><b>不做事的情况</b>：
 * <ul>
 *   <li>平台管理员（{@link TenantContext#isBypass()}）—— 需要跨企业视图</li>
 *   <li>无租户上下文（登录、定时任务等系统路径）—— 与改造前行为一致</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantGuard {

    private final TenantProperties tenantProperties;

    /**
     * 断言实体的企业归属与当前租户一致。
     *
     * @param entityEnterpriseId 实体的 {@code enterprise_id}（可为 null，视为不一致）
     * @param notFound           不一致时抛的错误码（通常是该实体的 XXX_NOT_FOUND）
     * @param scene              场景标识，便于从日志定位调用点
     */
    public void assertTenant(Long entityEnterpriseId, ErrorCode notFound, String scene) {
        if (TenantContext.isBypass()) {
            return;   // 平台管理员：跨企业正常工作
        }
        if (!TenantContext.isPresent()) {
            return;   // 系统路径（登录 / 定时任务）：与改造前一致
        }

        long current = TenantContext.require();
        boolean matches = entityEnterpriseId != null && entityEnterpriseId == current;
        if (matches) {
            return;
        }

        if (tenantProperties.isEnforce()) {
            log.warn("[tenant] 跨租户访问已拒绝 scene={} current={} entity={}",
                    scene, current, entityEnterpriseId);
            throw new BizException(notFound);
        }
        // 灰度期：只记录，不拒绝
        log.warn("[tenant] 跨租户访问（灰度期只记录，未拒绝）scene={} current={} entity={}",
                scene, current, entityEnterpriseId);
    }

    /**
     * 便捷版：一致返回 true，不一致返回 false（用于"可选"场景，由调用方自行决定降级）。
     *
     * <p>⚠️ 与 {@link #assertTenant} 不同，本方法**不受 {@code tenant.enforce} 影响**，
     * 也不抛异常——请只在确实需要"判断"而不是"拦截"的地方使用。
     */
    public boolean belongsToCurrentTenant(Long entityEnterpriseId) {
        if (TenantContext.isBypass() || !TenantContext.isPresent()) {
            return true;
        }
        return entityEnterpriseId != null && entityEnterpriseId == TenantContext.require();
    }
}
