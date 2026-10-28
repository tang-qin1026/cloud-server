package com.company.cloud.auth.portal;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务间认证配置（{@code app.service-auth.*}）。
 *
 * <p>依据《27 - 服务间认证改造：废弃 X-Internal-Key》§7 的产品侧对照表：
 * <ul>
 *   <li><b>删除</b> {@code PORTAL_INTERNAL_KEY}</li>
 *   <li><b>新增</b> {@code SERVICE_PRIVATE_KEY_FILE}（权限 600）、{@code SERVICE_KEY_ID}</li>
 *   <li><b>不变</b>：门户 JWKS 地址与刷新策略（12h + kid 未命中强刷）</li>
 * </ul>
 *
 * <p>产品侧**不需要新增"门户公钥"配置** —— 本来就通过 JWKS 拉取。
 *
 * <p>⚠️ 私钥只从**文件**读，不进库、不进 git、不进日志。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.service-auth")
public class ServiceAuthProperties {

    /** 本产品的 code（= JWT 的 {@code iss}，也是门户验签时与该 kid 所在行 owner_code 的比对值）。 */
    private String productCode = "storage";

    /** 门户的 {@code iss}（服务凭据的 {@code aud} 固定为它）。 */
    private String portalIssuer = "cloud-portal";

    /** 私钥文件路径（PKCS#8 PEM，权限 600）。为空则该功能未启用。 */
    private String privateKeyFile;

    /** 当前签名用的 kid，必须与门户 {@code p_service_key} 表里的行一致。 */
    private String keyId = "storage-2026-10";

    /** 服务凭据有效期（秒）。文档定 60 秒。 */
    private long ttlSeconds = 60;

    /** 总开关。关闭时两个方向都回退到旧的 X-Internal-Key。 */
    private boolean enabled = true;

    /**
     * **入站**开关：我们是否要求门户带 Bearer 来调用 /internal/**。
     *
     * <p>注意：入站验签只用**门户的公钥**（从 JWKS 拉），**不需要我们的私钥**。
     * 所以它与 {@code privateKeyFile} 无关 —— 这是刻意拆开的。
     */
    private boolean inboundEnabled = false;

    /**
     * **出站**开关：我们调门户时是否签服务凭据。需要我们的私钥。
     */
    private boolean outboundEnabled = false;

    /**
     * 入站**严格模式**：true 时只认 Bearer，带 X-Internal-Key 的请求一律拒绝。
     *
     * <p>默认 false = **双通道**：先试 Bearer，没有 Bearer 头时回退缩 X-Internal-Key。
     * 这样门户可以先上 Bearer、后停旧密钥，无需与产品侧同时切换。
     */
    private boolean inboundStrict = false;

    /** 入站是否就绪（只需总开关 + inboundEnabled）。 */
    public boolean isInboundReady() {
        return enabled && inboundEnabled;
    }

    /** 入站是否双通道（启用入站但未严格）。 */
    public boolean isInboundDualMode() {
        return isInboundReady() && !inboundStrict;
    }

    /** 出站是否就绪（需总开关 + outboundEnabled + 私钥与 kid）。 */
    public boolean isOutboundReady() {
        return enabled && outboundEnabled
                && privateKeyFile != null && !privateKeyFile.isBlank()
                && keyId != null && !keyId.isBlank();
    }

    /** 兼容旧调用：语义等同"出站就绪"。 */
    public boolean isConfigured() {
        return isOutboundReady();
    }
}
