package com.company.cloud.auth.tenant;

import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TenantGuard} 语义测试（第三道防线）。
 *
 * <p>锁住四条不变量：
 * <ol>
 *   <li>无租户上下文（登录/定时任务）→ **放行**，与改造前一致</li>
 *   <li>平台管理员 bypass → **放行**，否则跨企业视图全废</li>
 *   <li>归属一致 → 放行</li>
 *   <li>归属不一致 → {@code enforce=false} 只记日志；{@code enforce=true} 抛 404（**按不存在处理，不泄露存在性**）</li>
 * </ol>
 */
class TenantGuardTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static TenantGuard guard(boolean enforce) {
        TenantProperties props = new TenantProperties();
        props.setEnforce(enforce);
        return new TenantGuard(props);
    }

    @Test
    @DisplayName("无租户上下文 → 放行（登录 / 定时任务路径）")
    void noContextPasses() {
        assertDoesNotThrow(() ->
                guard(true).assertTenant(999L, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("平台管理员 bypass → 放行（跨企业视图）")
    void bypassPasses() {
        TenantContext.setBypass();
        assertDoesNotThrow(() ->
                guard(true).assertTenant(999L, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("归属一致 → 放行")
    void sameTenantPasses() {
        TenantContext.set(5L);
        assertDoesNotThrow(() ->
                guard(true).assertTenant(5L, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("归属不一致 + enforce=true → 抛 404（按不存在处理）")
    void mismatchThrowsWhenEnforced() {
        TenantContext.set(5L);
        BizException ex = assertThrows(BizException.class, () ->
                guard(true).assertTenant(9L, ErrorCode.USER_NOT_FOUND, "admin.user"));
        assertTrue(ex.getMessage().contains("不存在"), "应按「不存在」返回，不能泄露存在性：" + ex.getMessage());
    }

    @Test
    @DisplayName("归属不一致 + enforce=false → 只记日志不拒绝（灰度期）")
    void mismatchPassesWhenObserving() {
        TenantContext.set(5L);
        assertDoesNotThrow(() ->
                guard(false).assertTenant(9L, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("实体 enterpriseId 为 null → 视为不一致")
    void nullEntityFailsClosed() {
        TenantContext.set(5L);
        assertThrows(BizException.class, () ->
                guard(true).assertTenant(null, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("个人域用户（enterpriseId=0）只与 0 匹配")
    void personalDomain() {
        TenantContext.setPersonal();
        assertDoesNotThrow(() ->
                guard(true).assertTenant(0L, ErrorCode.USER_NOT_FOUND, "t"));
        assertThrows(BizException.class, () ->
                guard(true).assertTenant(5L, ErrorCode.USER_NOT_FOUND, "t"));
    }

    @Test
    @DisplayName("belongsToCurrentTenant 不受 enforce 影响、不抛异常")
    void belongsToIsSideEffectFree() {
        TenantContext.set(5L);
        assertTrue(guard(true).belongsToCurrentTenant(5L));
        assertFalse(guard(true).belongsToCurrentTenant(9L));
        assertFalse(guard(true).belongsToCurrentTenant(null));
        // 无上下文 → 视为属于（放行语义）
        TenantContext.clear();
        assertTrue(guard(true).belongsToCurrentTenant(9L));
    }
}
