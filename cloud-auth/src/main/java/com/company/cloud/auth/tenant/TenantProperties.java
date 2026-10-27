package com.company.cloud.auth.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 租户隔离配置（{@code tenant.*}）。
 *
 * <p>依据《24 - 企业版权限落地实施文档》v2 §6.2 的灰度方案：
 *
 * <pre>
 * tenant:
 *   enforce: false          # 第 1 周：只记录"本该拒绝"，不真拒绝
 *   filter-enabled: true    # 运维期间可整体关掉拦截（危险）
 * </pre>
 *
 * <p><b>{@link #enforce} 的语义</b>：
 * <ul>
 *   <li>{@code false}：第三道防线（显式断言）**只打日志不抛异常**；
 *       拦截器**照常**拼租户条件（灰度期也要正常读写 enterprise_id，转正时无需回填）</li>
 *   <li>{@code true}：跨企业访问**直接拒绝**</li>
 * </ul>
 *
 * <p>⚠️ 它**不**控制"是否拼租户条件"——那由 {@code TenantContext} 是否设置决定。
 * 否则灰度期会出现"部分接口隔离、部分没有"的混乱。
 *
 * <p>放在 cloud-auth 而不是 cloud-common：后者是纯 Java 模块，没有 Spring 依赖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tenant")
public class TenantProperties {

    /** 是否真拒绝跨企业访问。默认 false（灰度观察期）。 */
    private boolean enforce = false;

    /** 是否启用 MyBatis 租户拦截器。迁移/脚本期间可临时关掉（危险，仅限运维）。 */
    private boolean filterEnabled = true;
}