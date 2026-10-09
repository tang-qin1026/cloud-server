package com.company.cloud.auth.portal;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 门户接入 · 认证侧配置（app.portal.auth.*）。
 *
 * <p>依据《云平台统一门户 API 契约 v1.0》§1.5：门户签发的业务 JWT 为 RS256，
 * 公钥从门户 JWKS 端点获取，**启动拉取 + 每 12 小时刷新 + kid 未命中立即强刷**。
 *
 * <p>全部字段均可由环境变量覆盖（见 application.yml 的 app.portal.auth 段）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.portal.auth")
public class PortalAuthProperties {

    /** 门户 JWT 验签总开关；关闭后门户令牌一律不认证（仅本系统自签 JWT 可用）。 */
    private boolean enabled = true;

    /** 门户 JWKS 地址（裸 JWKS，不套 {code,data} 外壳）。 */
    private String jwksUrl = "http://localhost:3000/.well-known/jwks.json";

    /** 必须匹配的签发者 iss（契约要求固定为 cloud-portal）。 */
    private String issuer = "cloud-portal";

    /** 公钥缓存刷新间隔（小时）。契约要求 12 小时。 */
    private long refreshHours = 12;

    /** 定时刷新 cron（默认每 12 小时一次：0 分 0 秒，每 12 小时）。 */
    private String refreshCron = "0 0 */12 * * *";

    /** 拉取 JWKS 的连接超时（毫秒）。 */
    private int connectTimeoutMs = 3000;

    /** 拉取 JWKS 的读取超时（毫秒）。 */
    private int readTimeoutMs = 5000;

    /** 影子用户名的前缀（portal_<门户UUID>），便于运维一眼区分门户影子账号。 */
    private String shadowUsernamePrefix = "portal_";

    /** 影子用户默认配额（字节），默认 20GB，与 users.quota_bytes 默认值保持一致。 */
    private long shadowDefaultQuotaBytes = 21474836480L;

    /**
     * 影子用户占位密码哈希。
     *
     * <p>故意写成非法 bcrypt 串：门户影子用户**不允许**用本系统登录入口登录，
     * BCryptPasswordEncoder.matches 对该串必然返回 false。
     */
    private String shadowPasswordHash = "!portal-shadow-no-local-login";
}
