package com.company.cloud.portal.client;

import com.company.cloud.auth.portal.ServiceCredentialSigner;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.portal.config.PortalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 门户「企业目录」出站客户端（docs/29）。
 *
 * <p>两个接口：
 * <ul>
 *   <li>{@code GET /api/internal/enterprises} —— 企业清单（仅元数据）</li>
 *   <li>{@code GET /api/internal/enterprise-accounts} —— 某家企业的成员</li>
 * </ul>
 *
 * <p><b>为什么把查询串自己做规范化</b>：服务凭据的 {@code qs} 声明必须与实际发出的
 * query **逐字一致**。所以这里先用 {@link ServiceCredentialSigner#canonicalQuery} 把
 * 参数规范化成最终串，再同时用于「签名」和「URL」——不是签完再拼、也不是拼完再签。
 *
 * <p>鉴权优先服务凭据（RS256），未配置时回退旧的 {@code X-Internal-Key}
 * （与 {@link PortalWalletClient} 同一套回退策略）。
 */
@Slf4j
@Component
public class PortalDirectoryClient {

    private final PortalProperties props;
    private final RestClient restClient;

    /**
     * 服务凭据签发器；{@code required=false} 便于未配置私钥时回退旧密钥，
     * 也避免改动既有构造器签名。
     */
    @Autowired(required = false)
    private ServiceCredentialSigner serviceSigner;

    public PortalDirectoryClient(PortalProperties props) {
        this.props = props;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 企业清单。 */
    public PortalApiResponse listEnterprises(String status, String keyword, int limit, int offset) {
        Map<String, String> q = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            q.put("status", status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            q.put("keyword", keyword.trim());
        }
        q.put("limit", String.valueOf(limit));
        q.put("offset", String.valueOf(offset));
        return get(props.getEnterprisesPath(), q);
    }

    /** 某家企业的成员。 */
    public PortalApiResponse listEnterpriseAccounts(String enterpriseId, int limit, int offset) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("enterprise_id", enterpriseId);
        q.put("limit", String.valueOf(limit));
        q.put("offset", String.valueOf(offset));
        return get(props.getEnterpriseAccountsPath(), q);
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * GET + query 签名。
     *
     * <p>三步必须用**同一个** query 串：
     * <ol>
     *   <li>{@code qs} = 规范化后的串（key 升序、encodeURIComponent）</li>
     *   <li>签名时把 {@code qs} 作为声明传进去（signer 内部会再规范化一次，幂等）</li>
     *   <li>URL 直接用这个 {@code qs}</li>
     * </ol>
     */
    private PortalApiResponse get(String path, Map<String, String> query) {
        String qs = ServiceCredentialSigner.canonicalQuery(toRawQuery(query));
        String url = props.getBaseUrl() + path + (qs.isEmpty() ? "" : "?" + qs);

        String authHeader;
        String authValue;
        if (serviceSigner != null && serviceSigner.isReady()) {
            authHeader = "Authorization";
            authValue = "Bearer " + serviceSigner.sign("GET", path, qs, null);
        } else {
            authHeader = "X-Internal-Key";
            authValue = props.getInternalKey() == null ? "" : props.getInternalKey();
            log.debug("[portal] 服务凭据未启用，回退 X-Internal-Key path={}", path);
        }

        String raw;
        try {
            raw = restClient.get()
                    .uri(url)
                    .header(authHeader, authValue)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 门户可能用非 2xx 承载业务码；只要能解析成信封就按业务响应处理
            PortalApiResponse parsed = tryParse(e.getResponseBodyAsString());
            if (parsed != null) {
                log.warn("[portal] {} 返回 HTTP {} 业务码 {}（{}）",
                        path, e.getStatusCode(), parsed.code(), parsed.describe());
                return parsed;
            }
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "门户返回 HTTP " + e.getStatusCode() + "（响应体非 {code,...} 信封）");
        } catch (RestClientException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "门户调用失败: " + e.getMessage());
        }
        PortalApiResponse parsed = tryParse(raw);
        if (parsed == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "门户响应不可解析");
        }
        return parsed;
    }

    /** 用 {@code key=value&...} 拼原始 query（不做排序，交由 canonicalQuery 处理）。 */
    private static String toRawQuery(Map<String, String> q) {
        if (q == null || q.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private static PortalApiResponse tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(raw);
            if (!node.has("code")) {
                return null;
            }
            return new PortalApiResponse(
                    node.path("code").asInt(-1),
                    node.path("message").asText(""),
                    node.get("data"));
        } catch (Exception e) {
            return null;
        }
    }
}
