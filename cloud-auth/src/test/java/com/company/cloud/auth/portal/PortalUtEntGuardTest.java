package com.company.cloud.auth.portal;

import com.company.cloud.common.result.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「ut / ent 一致性」守卫的单元测试（门户契约硬要求）。
 *
 * <p>规则：{@code ut=1}（企业用户）必须带 {@code ent}（企业 id）。
 * 缺了必须**拒绝**，不能退化成个人域 —— 否则企业用户的数据会落到
 * {@code enterprise_id=0}，既是越权也是数据串号。
 *
 * <p><b>为什么用单测而不是打接口</b>：这条规则只有门户签发的 RS256 令牌才会触发，
 * 而门户私钥在产品侧拿不到，无法伪造一个"ut=1 但没有 ent"的合法令牌。
 * 所以直接验证守卫逻辑本身。
 *
 * <p>这里只覆盖 {@link PortalShadowUserService#resolveOrCreate} 的兜底守卫
 * （{@link PortalJwtAuthFilter} 里还有同样一条，且它排在验签之后，逻辑同构）。
 */
class PortalUtEntGuardTest {

    /**
     * 造一个只填了必要依赖的 service：守卫在任何 DB 访问**之前**就抛异常，
     * 所以其余依赖为 null 不影响本用例。
     */
    private static PortalShadowUserService newService() {
        PortalShadowUserService svc = new PortalShadowUserService(null, null, null, null);
        return svc;
    }

    @Test
    @DisplayName("ut=1 且 ent 缺失 → 拒绝，不能退化成个人域")
    void enterpriseUserWithoutEnterpriseIdIsRejected() {
        PortalShadowUserService svc = newService();
        BizException ex = assertThrows(BizException.class, () ->
                svc.resolveOrCreate("portal-uuid-1", "lcrA", 1, null, 1));
        assertTrue(ex.getMessage().contains("缺少企业归属"), "应明确说明缺少企业归属");
    }

    @Test
    @DisplayName("ut=1 且 ent=0 → 同样拒绝（0 不是合法企业 id）")
    void enterpriseUserWithZeroEnterpriseIdIsRejected() {
        PortalShadowUserService svc = newService();
        assertThrows(BizException.class, () ->
                svc.resolveOrCreate("portal-uuid-2", "lcrA", 1, 0L, 0));
    }

    @Test
    @DisplayName("ut=0（个人用户）不带 ent → 放行，不触发守卫")
    void personalUserWithoutEnterpriseIdPasses() {
        // 个人用户是合法场景：ut=0 表示个人域，没有 ent 属于正常。
        // 守卫放行后会继续走原来的建档逻辑；此处依赖为 null 会在更靠后的位置失败，
        // 但**不能**是"缺少企业归属"这个异常 —— 那才是本用例要证明的点。
        PortalShadowUserService svc = newService();
        Throwable t = null;
        try {
            svc.resolveOrCreate("portal-uuid-3", "personal", 0, null, null);
        } catch (Throwable e) {
            t = e;
        }
        if (t != null) {
            String msg = String.valueOf(t.getMessage());
            assertFalse(msg.contains("缺少企业归属"),
                    "个人用户不应被 ut/ent 守卫拦截，实际: " + msg);
        }
    }

    @Test
    @DisplayName("ut=1 且 ent 合法 → 通过守卫（失败点必须换到 DB 依赖）")
    void enterpriseUserWithEnterpriseIdPassesGuard() {
        PortalShadowUserService svc = newService();
        Throwable t = null;
        try {
            svc.resolveOrCreate("portal-uuid-4", "lcrA", 1, 7L, 1);
        } catch (Throwable e) {
            t = e;
        }
        assertNotNull(t, "依赖为 null，应当在本守卫之后失败");
        assertFalse(String.valueOf(t.getMessage()).contains("缺少企业归属"),
                "带合法 ent 时不应被 ut/ent 守卫拦截");
    }
}
