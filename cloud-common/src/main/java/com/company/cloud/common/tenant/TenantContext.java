package com.company.cloud.common.tenant;

/**
 * 租户上下文（请求级 ThreadLocal）。
 *
 * <p><b>为什么 {@link #require()} 不返回 0/null</b>：
 * 若返回 0，MyBatis 会把条件拼成 {@code enterprise_id = 0} → 查不到任何数据，
 * 表现为"数据突然消失"，比直接报错难查得多（《24 - 落地方案》§3.3-2）。
 *
 * <p><b>三种状态</b>：
 * <ul>
 *   <li>{@link #set(long)}：普通企业用户，读取时限定该企业</li>
 *   <li>{@link #setBypass()}：**平台管理员**（跨企业），拦截器不加条件</li>
 *   <li>未设置：登录等**尚无租户**的路径，拦截器同样不加条件</li>
 * </ul>
 *
 * <p>⚠️ **必须在请求结束时 {@link #clear()}**（认证过滤器 finally），否则线程复用会串租户。
 */
public final class TenantContext {

    /** 个人域的企业 id（与企业域隔离）。 */
    public static final long PERSONAL_ENTERPRISE_ID = 0L;

    private record Tenant(long enterpriseId, boolean bypass) {
    }

    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 企业用户：后续查询限定该企业。 */
    public static void set(long enterpriseId) {
        HOLDER.set(new Tenant(enterpriseId, false));
    }

    /** 个人域（未加入任何企业的用户）：限定 enterprise_id = 0。 */
    public static void setPersonal() {
        HOLDER.set(new Tenant(PERSONAL_ENTERPRISE_ID, false));
    }

    /** 平台管理员：跨企业，拦截器不加租户条件。 */
    public static void setBypass() {
        HOLDER.set(new Tenant(PERSONAL_ENTERPRISE_ID, true));
    }

    public static boolean isPresent() {
        return HOLDER.get() != null;
    }

    public static boolean isBypass() {
        Tenant t = HOLDER.get();
        return t != null && t.bypass();
    }

    /**
     * 取当前企业 id。
     *
     * @throws IllegalStateException 上下文未设置（**不允许静默返回 0**）
     */
    public static long require() {
        Tenant t = HOLDER.get();
        if (t == null) {
            throw new IllegalStateException(
                    "租户上下文缺失：请检查认证过滤器是否调用了 TenantContext.set(...)");
        }
        return t.enterpriseId();
    }

    /**
     * 供拦截器使用：未设置或平台管理员时返回 {@code null} 表示"不加条件"。
     *
     * <p>注意：业务代码**不要**用这个方法判断，应该用 {@link #require()}。
     */
    public static Long idOrNull() {
        Tenant t = HOLDER.get();
        if (t == null || t.bypass()) {
            return null;
        }
        return t.enterpriseId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
