package com.company.cloud.auth.portal;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 服务凭据签发器（产品 → 门户方向）。
 *
 * <p>依据《27 - 服务间认证改造》§3.1（claims）、§3.3（规范化规则）、§6.2（实现骨架）。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>用仓库已有的 <b>jjwt</b>，不引入 nimbus（避免为一个功能加依赖）</li>
 *   <li>私钥**只从文件读**（{@code SERVICE_PRIVATE_KEY_FILE}，权限 600），不进库、不进日志</li>
 *   <li>{@link #canonicalQuery} 与 {@link #canonicalPath} 必须与门户侧**逐字一致**，
 *       否则门户侧 {@code htu}/{@code qs} 比对必然失败 → 全量 1002</li>
 * </ul>
 *
 * <p>⚠️ <b>body hash 必须对"收到的原始字节"算</b>，绝不能反序列化再序列化 ——
 * 两种语言的字段顺序/空格处理不同，hash 必然不一致（文档 §3.3 特别点名）。
 */
@Slf4j
@Component
public class ServiceCredentialSigner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceAuthProperties props;
    private final PrivateKey privateKey;

    public ServiceCredentialSigner(ServiceAuthProperties props) {
        this.props = props;
        this.privateKey = loadPrivateKey(props);
        if (privateKey == null) {
            log.warn("[svc-auth] 未配置私钥（app.service-auth.private-key-file），服务间签名不可用");
        } else {
            log.info("[svc-auth] 服务凭据就绪 productCode={} kid={} ttl={}s",
                    props.getProductCode(), props.getKeyId(), props.getTtlSeconds());
        }
    }

    /** 私钥是否可用。 */
    public boolean isReady() {
        return privateKey != null && props.isConfigured();
    }

    /**
     * 签发一次服务凭据。
     *
     * @param method HTTP 方法（大写）
     * @param path   请求路径，**含 {@code /api} 前缀也可以**，本方法会按 §3.3 去掉
     * @param query  原始 query 串（可为 null/空）
     * @param body   请求体**原始字节**（无 body 传 null）
     */
    public String sign(String method, String path, String query, byte[] body) {
        if (!isReady()) {
            throw new IllegalStateException("服务凭据未配置：请设置 SERVICE_PRIVATE_KEY_FILE 与 SERVICE_KEY_ID");
        }
        Instant now = Instant.now();
        String jti = randomHex(16);
        return Jwts.builder()
                .header().keyId(props.getKeyId()).and()
                .issuer(props.getProductCode())
                .audience().add(props.getPortalIssuer()).and()
                .claim("htm", method == null ? "" : method.toUpperCase())
                .claim("htu", canonicalPath(path))
                .claim("qs", canonicalQuery(query))
                .claim("body_sha256", sha256Hex(body))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(props.getTtlSeconds())))
                .id(jti)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    // ── 规范化（必须与门户侧逐字一致，§3.3）─────────────────────

    /**
     * 路径规范化：去掉 {@code /api} 前缀、去掉末尾斜杠、保留大小写、不带域名。
     *
     * <pre>
     * /api/internal/enterprise-accounts  → /internal/enterprise-accounts
     * /api/internal/products/            → /internal/products
     * /internal/provision                → /internal/provision
     * </pre>
     */
    public static String canonicalPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        // 去掉 scheme://host（防御：调用方可能传完整 URL）
        int idx = p.indexOf("://");
        if (idx >= 0) {
            int slash = p.indexOf('/', idx + 3);
            p = slash >= 0 ? p.substring(slash) : "/";
        }
        if (p.startsWith("/api/")) {
            p = p.substring(4);
        } else if (p.equals("/api")) {
            p = "/";
        }
        // 去末尾斜杠（根路径除外）
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Query 规范化：按 key 字典序升序；key/value 做 encodeURIComponent；{@code &} 连接；空 query 为空串。
     *
     * <p>同名 key 出现多次时，**按 value 再排序**以保证确定性（文档未规定顺序时这是唯一稳定的做法）。
     */
    public static String canonicalQuery(String query) {
        if (query == null) {
            return "";
        }
        String q = query.trim();
        if (q.startsWith("?")) {
            q = q.substring(1);
        }
        if (q.isEmpty()) {
            return "";
        }
        Map<String, List<String>> byKey = new TreeMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            // 先解码再编码，避免调用方传已编码的值时二次编码
            byKey.computeIfAbsent(decode(k), x -> new ArrayList<>()).add(decode(v));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byKey.entrySet()) {
            List<String> vals = e.getValue();
            vals.sort(String::compareTo);
            for (String v : vals) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(encode(e.getKey())).append('=').append(encode(v));
            }
        }
        return sb.toString();
    }

    /** SHA-256（hex 小写）；body 为 null/空时返回空串。 */
    public static String sha256Hex(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(body);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ── 内部工具 ───────────────────────────────────────────────

    private static String encode(String s) {
        // 近似 encodeURIComponent：空格用 %20 而非 +，其余与 RFC3986 unreserved 对齐
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** 从文件读 PKCS#8 PEM 私钥；任何异常都返回 null（不 fail-fast，便于本地无密钥启动）。 */
    private static PrivateKey loadPrivateKey(ServiceAuthProperties props) {
        if (props.getPrivateKeyFile() == null || props.getPrivateKeyFile().isBlank()) {
            return null;
        }
        try {
            String pem = Files.readString(Path.of(props.getPrivateKeyFile()), StandardCharsets.UTF_8);
            String body = pem.replaceAll("-----BEGIN[^-]*-----", "")
                             .replaceAll("-----END[^-]*-----", "")
                             .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            log.warn("[svc-auth] 私钥文件读不到：{}（{}）", props.getPrivateKeyFile(), e.toString());
            return null;
        } catch (Exception e) {
            log.error("[svc-auth] 私钥解析失败：{}（{}）—— 请确认是 PKCS#8 PEM",
                    props.getPrivateKeyFile(), e.toString());
            return null;
        }
    }
}
