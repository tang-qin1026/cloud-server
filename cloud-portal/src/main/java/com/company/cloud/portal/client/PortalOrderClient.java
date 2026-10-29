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
import java.util.List;
import java.util.Map;

/**
 * 门户「服务端下单」出站客户端（docs/30）。
 *
 * <p>两个接口：
 * <ul>
 *   <li>{@code POST /api/internal/orders} —— 下单并支付（合成一步：下单 + 扣款 + 入队开通）</li>
 *   <li>{@code GET /api/internal/orders/{order_id}} —— 查开通结果</li>
 * </ul>
 *
 * <p><b>为什么需要它</b>：产品后端拿不到用户的门户 {@code access_token} 时
 * （我们的账号体系是本地 + 门户回落，登录后并不保存用户的门户 token），
 * 只能用**产品服务凭据 + portal_user_id** 替用户下单。
 *
 * <h3>三条铁律</h3>
 * <ol>
 *   <li>{@code body_sha256} 必须对**实际发出的那段 body 字节**计算。
 *       这里先序列化成 {@code byte[]}，签名与发送用同一份 ——
 *       绝不能"签一次序列化、发时再序列化一遍"（字段顺序/空格任何差异都会 1002）。</li>
 *   <li>{@code clientOrderKey} 由**同一个业务动作**恒定复用：
 *       每次重试换新 key = 真的重复下单、重复扣钱。</li>
 *   <li>{@code duplicated=true} 视为成功（返回首次结果，本次没扣款）。</li>
 * </ol>
 */
@Slf4j
@Component
public class PortalOrderClient {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final PortalProperties props;
    private final RestClient restClient;

    /** 服务凭据签发器；未配置私钥时回退旧 {@code X-Internal-Key}。 */
    @Autowired(required = false)
    private ServiceCredentialSigner serviceSigner;

    public PortalOrderClient(PortalProperties props) {
        this.props = props;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        // 下单涉及门户侧扣款 + 入队，给比默认更宽的读超时
        factory.setReadTimeout(Math.max(props.getReadTimeoutMs(), 15000));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 下单并支付。
     *
     * @param portalUserId   **门户**用户 id（UUID）。必须由映射表取得，绝不能来自用户输入
     * @param clientOrderKey 幂等键（8~64 位，字母/数字/{@code ._:-}）
     * @param items          每项 {@code {product_code, sku_id, quantity}}；
     *                       {@code product_code} 必须等于本产品 code，否则门户返回 1004
     * @param consentRef     用户在产品内点"购买"时产生的确认号（可空，但强烈建议传）
     */
    public PortalApiResponse createOrder(String portalUserId, String clientOrderKey,
                                         List<Map<String, Object>> items, String consentRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("portal_user_id", portalUserId);
        body.put("client_order_key", clientOrderKey);
        body.put("items", items);
        if (consentRef != null && !consentRef.isBlank()) {
            body.put("consent_ref", consentRef.trim());
        }
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "下单请求体序列化失败");
        }
        // qs 空串：本接口没有 query
        return send("POST", props.getOrdersPath(), "", payload);
    }

    /** 查开通结果。{@code provision_done=true} 就不用再轮询。 */
    public PortalApiResponse getOrder(String orderId) {
        String path = props.getOrdersPath() + "/" + orderId;
        return send("GET", path, "", null);
    }

    // ── 内部 ──────────────────────────────────────────────────

    private PortalApiResponse send(String method, String path, String qs, byte[] payload) {
        String url = props.getBaseUrl() + path + (qs.isEmpty() ? "" : "?" + qs);

        String authHeader;
        String authValue;
        if (serviceSigner != null && serviceSigner.isReady()) {
            authHeader = "Authorization";
            authValue = "Bearer " + serviceSigner.sign(method, path, qs, payload);
        } else {
            authHeader = "X-Internal-Key";
            authValue = props.getInternalKey() == null ? "" : props.getInternalKey();
            log.debug("[portal] 服务凭据未启用，回退 X-Internal-Key path={}", path);
        }

        String raw;
        try {
            var spec = "GET".equals(method)
                    ? restClient.get().uri(url).header(authHeader, authValue).accept(MediaType.APPLICATION_JSON)
                    : restClient.post().uri(url).header(authHeader, authValue)
                            .contentType(MediaType.APPLICATION_JSON).body(payload);
            raw = spec.retrieve().body(String.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
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

    private static PortalApiResponse tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var node = JSON.readTree(raw);
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
