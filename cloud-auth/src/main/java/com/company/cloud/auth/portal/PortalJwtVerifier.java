package com.company.cloud.auth.portal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.PublicKey;

/**
 * 门户 JWT 验签（契约 §1.5 / §5.2）。
 *
 * <p>校验项：
 * <ul>
 *   <li>签名算法必须为 <b>RS256</b>（用门户 JWKS 公钥验签；alg 被篡改直接拒绝）</li>
 *   <li>{@code iss} 必须等于 {@code app.portal.auth.issuer}（默认 cloud-portal）</li>
 *   <li>{@code exp} 自动校验（jjwt 内建）</li>
 * </ul>
 *
 * <p><b>不回调门户</b>：验签完全本地完成（公钥走 {@link PortalJwksClient} 缓存）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalJwtVerifier {

    private final PortalAuthProperties props;
    private final PortalJwksClient jwksClient;

    /**
     * 验签并解析门户令牌。
     *
     * @param token 不含 "Bearer " 前缀的原始 JWT
     * @return 门户身份（sub / uname）
     * @throws JwtException 验签失败、算法不符、iss 不符、已过期、缺 sub
     */
    public PortalToken verify(String token) {
        Claims claims = Jwts.parser()
                // 注意：keyLocator 的形参是 Locator<? super Key>，
                // 必须用 LocatorAdapter<Key>；写成 LocatorAdapter<PublicKey> 无法通过编译。
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(ProtectedHeader header) {
                        String alg = header.getAlgorithm();
                        if (!"RS256".equals(alg)) {
                            // 拒绝 HS256 等降级攻击
                            throw new UnsupportedJwtException("门户令牌算法必须为 RS256，实际=" + alg);
                        }
                        String kid = header.getKeyId();
                        PublicKey key = jwksClient.locate(kid);
                        if (key == null) {
                            throw new UnsupportedJwtException("门户公钥未找到，kid=" + kid);
                        }
                        return key;
                    }
                })
                .requireIssuer(props.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new JwtException("门户令牌缺少 sub");
        }
        String uname = claims.get("uname", String.class);
        return new PortalToken(sub, uname);
    }

    /** 门户身份：sub = 门户用户 UUID；uname = 门户用户名（可空，仅用于展示/排障）。 */
    public record PortalToken(String portalUserId, String username) {
    }
}
