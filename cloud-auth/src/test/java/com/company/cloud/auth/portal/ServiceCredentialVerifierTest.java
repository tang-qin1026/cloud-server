package com.company.cloud.auth.portal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务凭据入站校验测试（《27》§4.2）。
 *
 * <p>重点是**攻击用例**：每条都必须被拒绝（对应文档 §9.2）。
 */
class ServiceCredentialVerifierTest {

    private static final String PORTAL_ISS = "cloud-portal";
    private static final String PRODUCT = "storage";
    private static final String KID = "portal-2026-10";

    private static KeyPair portalKeyPair;

    private final ServiceCredentialVerifier verifier = new ServiceCredentialVerifier();
    private final ServiceCredentialVerifier.PublicKeyResolver resolver = kid ->
            KID.equals(kid) ? portalKeyPair.getPublic() : null;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        portalKeyPair = g.generateKeyPair();
    }

    /** 造一个合法 token，允许各处被篡改。 */
    private String token(String iss, String aud, String htm, String htu, String qs,
                         String bodyHash, Date iat, Date exp, String kid) {
        var b = Jwts.builder()
                .issuer(iss)
                .audience().add(List.of(aud)).and()
                .claim("htm", htm)
                .claim("htu", htu)
                .claim("qs", qs)
                .claim("body_sha256", bodyHash)
                .claim("order_item_id", 50001)
                .issuedAt(iat)
                .expiration(exp)
                .id("jti-1");
        if (kid != null) {
            b = b.header().keyId(kid).and();
        }
        return b.signWith(portalKeyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private String goodToken(byte[] body, String method, String path, String query) {
        Date now = new Date();
        return token(PORTAL_ISS, PRODUCT, method,
                ServiceCredentialSigner.canonicalPath(path),
                ServiceCredentialSigner.canonicalQuery(query),
                ServiceCredentialSigner.sha256Hex(body),
                now, new Date(now.getTime() + 60_000), KID);
    }

    private ServiceCredentialVerifier.Result verify(String tok, byte[] body, String method,
                                                    String path, String query) {
        return verifier.verify(tok, body, method, path, query, PRODUCT, PORTAL_ISS, resolver);
    }

    // ── 正常路径 ──────────────────────────────────────────────

    @Test
    @DisplayName("合法 token 通过（无 body）")
    void goodTokenWithoutBody() {
        String tok = goodToken(null, "GET", "/api/internal/products", "");
        var r = verify(tok, null, "GET", "/api/internal/products", null);
        assertTrue(r.ok(), r.reason());
        assertNotNull(r.claims());
    }

    @Test
    @DisplayName("合法 token 通过（有 body，hash 一致）")
    void goodTokenWithBody() {
        byte[] body = "{\"order_item_id\":50001,\"sku_id\":\"s1\"}".getBytes();
        String tok = goodToken(body, "POST", "/api/internal/provision", "");
        var r = verify(tok, body, "POST", "/api/internal/provision", "");
        assertTrue(r.ok(), r.reason());
    }

    @Test
    @DisplayName("order_item_id 一致才通过")
    void orderItemMustMatch() {
        byte[] body = "{\"order_item_id\":50001}".getBytes();
        String tok = goodToken(body, "POST", "/api/internal/provision", "");
        var r = verify(tok, body, "POST", "/api/internal/provision", "");
        assertTrue(r.ok());
        assertTrue(ServiceCredentialVerifier.orderItemMatches(r.claims(), 50001));
        // 拿合法 token 配另一个 body → 必须不匹配
        assertFalse(ServiceCredentialVerifier.orderItemMatches(r.claims(), 50002));
    }

    // ── 攻击用例（§9.2，每条都必须被拒）────────────────────────

    @Test
    @DisplayName("伪造 kid → 拒绝")
    void wrongKid() {
        String tok = goodToken(null, "GET", "/internal/products", "");
        var r = verifier.verify(tok, null, "GET", "/internal/products", null,
                PRODUCT, PORTAL_ISS, kid -> null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("kid"));
    }

    @Test
    @DisplayName("iss 不对 → 拒绝")
    void wrongIssuer() {
        Date now = new Date();
        String tok = token("evil-issuer", PRODUCT, "GET", "/internal/products", "", "",
                now, new Date(now.getTime() + 60_000), KID);
        assertFalse(verify(tok, null, "GET", "/internal/products", null).ok());
    }

    @Test
    @DisplayName("aud 不对（给别家用的 token）→ 拒绝")
    void wrongAudience() {
        Date now = new Date();
        String tok = token(PORTAL_ISS, "phone", "GET", "/internal/products", "", "",
                now, new Date(now.getTime() + 60_000), KID);
        var r = verify(tok, null, "GET", "/internal/products", null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("aud"));
    }

    @Test
    @DisplayName("换 HTTP 方法 → 拒绝（只读 token 调写接口）")
    void methodMismatch() {
        String tok = goodToken(null, "GET", "/internal/products", "");
        var r = verify(tok, null, "DELETE", "/internal/products", null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("htm"));
    }

    @Test
    @DisplayName("换路径 → 拒绝")
    void pathMismatch() {
        String tok = goodToken(null, "GET", "/internal/products", "");
        var r = verify(tok, null, "GET", "/internal/provision", null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("htu"));
    }

    @Test
    @DisplayName("换查询条件（如 enterprise_id 看别家）→ 拒绝")
    void queryMismatch() {
        byte[] none = null;
        Date now = new Date();
        String tok = token(PORTAL_ISS, PRODUCT, "GET",
                ServiceCredentialSigner.canonicalPath("/internal/enterprise-accounts"),
                ServiceCredentialSigner.canonicalQuery("enterprise_id=1"),
                "", now, new Date(now.getTime() + 60_000), KID);
        var r = verify(tok, none, "GET", "/internal/enterprise-accounts", "enterprise_id=2");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("qs"));
    }

    @Test
    @DisplayName("改 body（改金额）→ 拒绝")
    void bodyTampered() {
        byte[] original = "{\"amount_cents\":100}".getBytes();
        byte[] tampered = "{\"amount_cents\":99999}".getBytes();
        String tok = goodToken(original, "POST", "/internal/provision", "");
        var r = verify(tok, tampered, "POST", "/internal/provision", "");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("body_sha256"));
    }

    @Test
    @DisplayName("有 body 但 token 没带 body_sha256 → 拒绝")
    void missingBodyHash() {
        Date now = new Date();
        String tok = token(PORTAL_ISS, PRODUCT, "POST",
                ServiceCredentialSigner.canonicalPath("/internal/provision"),
                "", null, now, new Date(now.getTime() + 60_000), KID);
        var r = verify(tok, "{\"a\":1}".getBytes(), "POST", "/internal/provision", "");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("body_sha256"));
    }

    @Test
    @DisplayName("过期 token → 拒绝")
    void expired() {
        Date past = new Date(System.currentTimeMillis() - 10 * 60_000);
        String tok = token(PORTAL_ISS, PRODUCT, "GET", "/internal/products", "", "",
                past, new Date(past.getTime() + 60_000), KID);
        var r = verify(tok, null, "GET", "/internal/products", null);
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("iat 偏差过大（重放旧 token）→ 拒绝")
    void iatTooOld() {
        Date old = new Date(System.currentTimeMillis() - 30 * 60_000);
        String tok = token(PORTAL_ISS, PRODUCT, "GET", "/internal/products", "", "",
                old, new Date(old.getTime() + 3600_000), KID);
        var r = verify(tok, null, "GET", "/internal/products", null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("iat"));
    }

    @Test
    @DisplayName("缺少令牌 → 拒绝")
    void noToken() {
        assertFalse(verify(null, null, "GET", "/internal/products", null).ok());
        assertFalse(verify("", null, "GET", "/internal/products", null).ok());
    }

    @Test
    @DisplayName("非 RS256（这里是乱码 token）→ 拒绝")
    void malformedToken() {
        assertFalse(verify("not-a-jwt", null, "GET", "/internal/products", null).ok());
    }
}
