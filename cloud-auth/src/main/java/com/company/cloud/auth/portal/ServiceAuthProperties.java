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

    /** 是否启用服务凭据（关闭时回退到旧的 X-Internal-Key，便于灰度/回滚）。 */
    private boolean enabled = true;

    public boolean isConfigured() {
        return enabled && privateKeyFile != null && !privateKeyFile.isBlank()
                && keyId != null && !keyId.isBlank();
    }
}
