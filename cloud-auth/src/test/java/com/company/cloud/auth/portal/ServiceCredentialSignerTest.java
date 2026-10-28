package com.company.cloud.auth.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务凭据规范化规则测试（《27》§3.3）。
 *
 * <p>这组规则必须与门户侧**逐字一致**：任一处不一致，门户侧 {@code htu}/{@code qs}
 * 比对必然失败 → 全量 {@code 1002}。所以这里逐条锁死。
 */
class ServiceCredentialSignerTest {

    // ── path 规范化 ────────────────────────────────────────────

    @Test
    @DisplayName("path 去掉 /api 前缀")
    void stripApiPrefix() {
        assertEquals("/internal/enterprise-accounts",
                ServiceCredentialSigner.canonicalPath("/api/internal/enterprise-accounts"));
        assertEquals("/internal/provision",
                ServiceCredentialSigner.canonicalPath("/api/internal/provision"));
    }

    @Test
    @DisplayName("path 去掉末尾斜杠（根除外）")
    void stripTrailingSlash() {
        assertEquals("/internal/products",
                ServiceCredentialSigner.canonicalPath("/api/internal/products/"));
        assertEquals("/", ServiceCredentialSigner.canonicalPath("/api/"));
        assertEquals("/", ServiceCredentialSigner.canonicalPath("/api"));
    }

    @Test
    @DisplayName("path 保留大小写，且不带域名")
    void keepCaseAndStripHost() {
        assertEquals("/internal/Enterprise-Accounts",
                ServiceCredentialSigner.canonicalPath("/api/internal/Enterprise-Accounts"));
        // 完整 URL：先去掉域名，再去掉 /api 前缀 → /x
        assertEquals("/x",
                ServiceCredentialSigner.canonicalPath("https://portal.example.com/api/x"));
        // 不带 /api 的路径保持原样
        assertEquals("/internal/products",
                ServiceCredentialSigner.canonicalPath("https://portal.example.com/internal/products"));
    }

    // ── query 规范化 ───────────────────────────────────────────

    @Test
    @DisplayName("query 按 key 字典序升序")
    void sortByKey() {
        assertEquals("a=1&b=2&c=3",
                ServiceCredentialSigner.canonicalQuery("c=3&a=1&b=2"));
    }

    @Test
    @DisplayName("空 query → 空串")
    void emptyQuery() {
        assertEquals("", ServiceCredentialSigner.canonicalQuery(null));
        assertEquals("", ServiceCredentialSigner.canonicalQuery(""));
        assertEquals("", ServiceCredentialSigner.canonicalQuery("?"));
    }

    @Test
    @DisplayName("query 值做百分号编码，空格为 %20 不是 +")
    void encodeValues() {
        assertEquals("keyword=a%20b", ServiceCredentialSigner.canonicalQuery("keyword=a b"));
    }

    @Test
    @DisplayName("已编码的 query 不会被二次编码")
    void noDoubleEncoding() {
        assertEquals("keyword=a%20b", ServiceCredentialSigner.canonicalQuery("keyword=a%20b"));
    }

    @Test
    @DisplayName("同名 key 按 value 再排序（保证确定性）")
    void sameKeySortedByValue() {
        assertEquals("t=1&t=2&t=3", ServiceCredentialSigner.canonicalQuery("t=3&t=1&t=2"));
    }

    @Test
    @DisplayName("无 = 的 key 按空值处理")
    void keyWithoutValue() {
        assertEquals("flag=", ServiceCredentialSigner.canonicalQuery("flag"));
    }

    // ── body hash ─────────────────────────────────────────────

    @Test
    @DisplayName("body hash 与已知 SHA-256 一致")
    void bodyHashKnownValue() {
        // "abc" 的 SHA-256
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ServiceCredentialSigner.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("空 body → 空串（文档：有 body 时才填）")
    void emptyBody() {
        assertEquals("", ServiceCredentialSigner.sha256Hex(null));
        assertEquals("", ServiceCredentialSigner.sha256Hex(new byte[0]));
    }

    @Test
    @DisplayName("body hash 对字节敏感：同样的 JSON 换空格就不同")
    void bodyHashIsByteSensitive() {
        String a = ServiceCredentialSigner.sha256Hex("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String b = ServiceCredentialSigner.sha256Hex("{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8));
        assertFalse(a.equals(b), "字节不同必须 hash 不同（所以绝不能用反序列化再序列化）");
    }

    // ── 未配置时不应可用 ──────────────────────────────────────

    @Test
    @DisplayName("未配置私钥时 isReady=false，sign 抛异常（不静默签出空 token）")
    void notConfiguredFailsFast() {
        ServiceAuthProperties props = new ServiceAuthProperties();
        props.setPrivateKeyFile(null);
        ServiceCredentialSigner signer = new ServiceCredentialSigner(props);
        assertFalse(signer.isReady());
        boolean threw = false;
        try {
            signer.sign("GET", "/api/internal/products", null, null);
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "未配置时必须抛异常，不能签出无法验证的 token");
    }
}
