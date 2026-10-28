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

        // 租户三件套（V2025/M2）。兼容数字与字符串两种编码：门户可能把 ent 序列化成字符串。
        Integer ut = asInt(claims.get("ut"));
        Long ent = asLong(claims.get("ent"));
        Integer er = asInt(claims.get("er"));

        // ut=1（企业员工）却没有 ent → 必须拒绝。
        // 若放行并退化成个人域，企业数据会写进 enterprise_id=0，事后无法区分归属。
        if (ut != null && ut == 1 && ent == null) {
            throw new JwtException("企业员工令牌缺少 ent");
        }

        return new PortalToken(sub, uname, ut, ent, er);
    }

    /** 门户身份：sub = 门户用户 UUID；uname = 门户用户名（可空，仅用于展示/排障）。 */
    public record PortalToken(String portalUserId, String username,
                              Integer userType, Long enterpriseId, Integer enterpriseRole) {

        /** 兼容两参构造（旧调用点/测试）。 */
        public PortalToken(String portalUserId, String username) {
            this(portalUserId, username, null, null, null);
        }
    }
    /** JWT claim 可能是数字、字符串或缺失，统一转 Integer。 */
    private static Integer asInt(Object v) {
        if (v == null) { return null; }
        if (v instanceof Number n) { return n.intValue(); }
        String s = v.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    /** 同上，转 Long。 */
    private static Long asLong(Object v) {
        if (v == null) { return null; }
        if (v instanceof Number n) { return n.longValue(); }
        String s = v.toString().trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }
}