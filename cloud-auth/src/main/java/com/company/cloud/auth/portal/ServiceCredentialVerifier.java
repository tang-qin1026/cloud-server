package com.company.cloud.auth.portal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 服务凭据**入站**校验器（门户 → 产品方向）。
 *
 * <p>依据《27 - 服务间认证改造》§3.2（claims）、§3.3（规范化）、§4.2（校验规则）。
 *
 * <p><b>七个校验点，任一不过即拒绝</b>：
 * <ol>
 *   <li>{@code alg} 必须是 RS256（显式白名单，拒绝 {@code none}/{@code HS256} 降级攻击）</li>
 *   <li>{@code iss} 必须等于 {@code cloud-portal}</li>
 *   <li>{@code aud} 必须包含本产品 code（防"给别家用的 token 塞给我们"）</li>
 *   <li>{@code htm} / {@code htu} / {@code qs} 与实际请求逐字比对（防换路径/改查询条件）</li>
 *   <li>{@code body_sha256} 与**收到的原始字节**一致（防篡改金额等字段）</li>
 *   <li>时钟：{@code |now - iat| ≤ 120s} 且 {@code now < exp}</li>
 *   <li>{@code order_item_id}（若使用方提供 body 值则比对）—— 由调用方传期望值</li>
 * </ol>
 *
 * <p>⚠️ <b>body hash 必须用原始字节</b>，不能反序列化再序列化（§3.3 点名）。
 * 所以本类签名接受 {@code byte[] rawBody}，由过滤器在读流时缓存。
 */
@Slf4j
@Component
public class ServiceCredentialVerifier {

    /** 时钟容忍窗口（秒）。§4.1 给门户侧定了 120s，产品侧对称。 */
    private static final long CLOCK_SKEW_SECONDS = 120;

    /** kid → 公钥。由调用方注入（门户 JWKS 缓存）。 */
    public interface PublicKeyResolver {
        PublicKey resolve(String kid);
    }

    /** 校验结果；失败时 {@code reason} 只用于服务端日志，**不回给调用方**（§4.1：区分原因等于给探测信号）。 */
    public record Result(boolean ok, String reason, Claims claims) {
        static Result fail(String reason) {
            return new Result(false, reason, null);
        }

        static Result pass(Claims claims) {
            return new Result(true, null, claims);
        }
    }

    /**
     * 校验门户下发的服务凭据。
     *
     * @param token          Authorization: Bearer 后面的 JWT
     * @param rawBody        请求体原始字节（无 body 传 null）
     * @param method         HTTP 方法（大写）
     * @param path           请求路径（可含 /api 前缀）
     * @param query          原始 query 串
     * @param expectedAud    本产品 code（如 storage）
     * @param portalIssuer   门户 iss（如 cloud-portal）
     * @param resolver       kid → 公钥
     */
    public Result verify(String token, byte[] rawBody, String method, String path, String query,
                         String expectedAud, String portalIssuer, PublicKeyResolver resolver) {
        if (token == null || token.isBlank()) {
            return Result.fail("缺少令牌");
        }

        // ① 先解 header 拿 kid，再解析（不信任未验签的内容）
        String kid;
        try {
            String headerJson = new String(java.util.Base64.getUrlDecoder()
                    .decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode h =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(headerJson);
            if (!"RS256".equals(h.path("alg").asText())) {
                return Result.fail("alg 不是 RS256，拒绝降级攻击");
            }
            kid = h.path("kid").asText("");
            if (kid.isBlank()) {
                return Result.fail("缺少 kid");
            }
        } catch (Exception e) {
            return Result.fail("header 解析失败：" + e.getMessage());
        }

        PublicKey key = resolver.resolve(kid);
        if (key == null) {
            return Result.fail("kid 未命中：" + kid);
        }

        // ② 验签 + iss/aud
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(portalIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return Result.fail("验签或 iss 校验失败：" + e.getMessage());
        }

        // ③ aud（jjwt 的 requireAudience 单个值会严格比对；这里手动做包含判断，兼容多 aud）
        if (claims.getAudience() == null || !claims.getAudience().contains(expectedAud)) {
            return Result.fail("aud 不匹配，期望 " + expectedAud);
        }

        // ④ 时钟：|now - iat| ≤ 120s 且 now < exp
        Date iat = claims.getIssuedAt();
        Date exp = claims.getExpiration();
        if (iat == null || exp == null) {
            return Result.fail("缺少 iat/exp");
        }
        long now = Instant.now().getEpochSecond();
        long skew = Math.abs(now - iat.toInstant().getEpochSecond());
        if (skew > CLOCK_SKEW_SECONDS) {
            return Result.fail("iat 偏差过大：" + skew + "s");
        }
        if (now >= exp.toInstant().getEpochSecond()) {
            return Result.fail("已过期");
        }

        // ⑤ 请求绑定：htm / htu / qs
        String claimHtm = claims.get("htm", String.class);
        if (claimHtm == null || !claimHtm.equalsIgnoreCase(method)) {
            return Result.fail("htm 不匹配：" + claimHtm + " vs " + method);
        }
        String claimHtu = claims.get("htu", String.class);
        String actualHtu = canonicalPath(path);
        if (claimHtu == null || !claimHtu.equals(actualHtu)) {
            return Result.fail("htu 不匹配：" + claimHtu + " vs " + actualHtu);
        }
        String claimQs = claims.get("qs", String.class);
        String actualQs = canonicalQuery(query);
        if (claimQs != null && !claimQs.equals(actualQs)) {
            return Result.fail("qs 不匹配");
        }

        // ⑥ body hash
        String claimBodyHash = claims.get("body_sha256", String.class);
        String actualBodyHash = ServiceCredentialSigner.sha256Hex(rawBody);
        boolean hasBody = rawBody != null && rawBody.length > 0;
        if (hasBody) {
            if (claimBodyHash == null || claimBodyHash.isBlank()) {
                return Result.fail("有请求体但缺少 body_sha256");
            }
            if (!claimBodyHash.equals(actualBodyHash)) {
                return Result.fail("body_sha256 不匹配");
            }
        }

        return Result.pass(claims);
    }

    /**
     * 校验 token 里的 {@code order_item_id} 与业务 body 里的一致。
     *
     * <p>§3.2 明确要求：否则攻击者能拿一个合法 token 配另一个 body。
     */
    public static boolean orderItemMatches(Claims claims, Object bodyOrderItemId) {
        if (claims == null) {
            return false;
        }
        Object claimVal = claims.get("order_item_id");
        if (claimVal == null || bodyOrderItemId == null) {
            return false;
        }
        return String.valueOf(claimVal).equals(String.valueOf(bodyOrderItemId));
    }

    /** 与签发器保持同一套规范化（复用静态方法，避免两边漂移）。 */
    private static String canonicalPath(String path) {
        return ServiceCredentialSigner.canonicalPath(path);
    }

    private static String canonicalQuery(String query) {
        return ServiceCredentialSigner.canonicalQuery(query);
    }

    /** 给日志用的简短描述。 */
    public static String describe(Date exp) {
        if (exp == null) {
            return "no-exp";
        }
        long left = ChronoUnit.SECONDS.between(Instant.now(), exp.toInstant());
        return "exp-in-" + left + "s";
    }
}
