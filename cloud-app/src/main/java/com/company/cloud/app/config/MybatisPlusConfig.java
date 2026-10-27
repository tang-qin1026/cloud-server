package com.company.cloud.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.company.cloud.common.tenant.TenantContext;
import com.company.cloud.auth.tenant.TenantProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/**
 * MyBatis-Plus 配置：**租户隔离** + 分页。
 *
 * <p>依据《24 - 企业版权限落地实施文档》v2 §3.3（租户隔离三道防线之一）：
 * 本拦截器负责 **MyBatis 侧**的所有 SQL；JPA 侧由 Hibernate {@code @TenantId} 负责；
 * 两者都绕过的路径靠服务层**显式断言**（第三道）。
 *
 * <p><b>采用白名单而不是黑名单</b>：只有**真正带 {@code enterprise_id} 列**的表才自动加条件。
 * 黑名单的失败模式是"新增表忘了排除 → SQL 去查不存在的列 → 直接报错"；
 * 白名单的失败模式是"该隔离的表没隔离"，更容易在评审与灰度日志里发现。
 * **M4（文件租户化）完成后要把 {@code files} 加进白名单。**
 *
 * <p><b>什么时候不加条件</b>（{@link TenantLineHandler#ignoreTable}）：
 * <ol>
 *   <li>表不在白名单 —— 它没有 {@code enterprise_id} 列</li>
 *   <li>{@link TenantContext} 未设置 —— 登录等"还没有租户"的路径（按 username 查 users 必须跨域）</li>
 *   <li>{@link TenantContext#isBypass()} —— 平台管理员，需要跨企业视图</li>
 * </ol>
 *
 * <p>⚠️ 分页拦截器**必须最后加**（MyBatis-Plus 要求）。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 需要自动加 {@code enterprise_id} 条件的表（**必须确实有该列**）。
     *
     * <p>M2：users / audit_logs / dept / role / storage_resource。M4 追加 files。
     *
     * <p>不包含：{@code enterprise}（全局，按 id 直查）、{@code permission}（全局字典）、
     * {@code role_permission} / {@code user_role}（无该列，靠关联的 role/users 间接隔离）、
     * {@code portal_*} / {@code billing_config}（产品级基础设施）。
     */
    private static final Set<String> TENANT_TABLES = Set.of(
            "users", "audit_logs", "dept", "role", "storage_resource");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        if (tenantProperties.isFilterEnabled()) {
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

                @Override
                public Expression getTenantId() {
                    Long id = TenantContext.idOrNull();
                    if (id == null) {
                        // 不该发生：ignoreTable 已保证"有上下文且非 bypass"才会走到这里
                        throw new IllegalStateException("租户条件构建失败：上下文缺失");
                    }
                    return new LongValue(id);
                }

                @Override
                public String getTenantIdColumn() {
                    return "enterprise_id";
                }

                @Override
                public boolean ignoreTable(String tableName) {
                    if (tableName == null) {
                        return true;
                    }
                    // 无上下文 / 平台管理员 → 不加条件
                    if (TenantContext.idOrNull() == null) {
                        return true;
                    }
                    return !TENANT_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
                }
            }));
        }

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);   // ⚠️ 必须最后

        return interceptor;
    }
}
