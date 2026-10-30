package com.company.cloud.portal.client;

import com.company.cloud.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门户内网接口客户端（契约 §4.3 / §4.4 / §6）。
 *
 * <p>统一带 {@code X-Internal-Key}；**系统类失败**抛出 {@link PortalTransportException}
 * 供上层按「同一 bill_no 退避重试」处理，**业务类失败**正常返回响应体由上层分类。
 *
 * <p>响应体字段的设计依据平台侧 2026-10-09 的反馈：
 * <ul>
 *   <li><b>扣费</b>：{@code product_code} 传不传都行，但**传了必须等于密钥对应的产品**；
 *       本侧配的就是 {@code storage}，故照常携带（显式声明比省略更可控）。</li>
 *   <li><b>退款</b>：**不必传 {@code product_code}**——门户改为「查原单归属」判定，
 *       密钥只能退自己产生的账单。因此退款体严格按契约 §4.4 只发
 *       {@code bill_no / refund_bill_no / amount_cents / reason}，
 *       不再附带 {@code product_code} 与 {@code portal_user_id}，避免多余字段触发 1004
 *       （「密钥与账单/声明不匹配」）。</li>
 * </ul>
 */
@Slf4j
@Component
public class PortalWalletClient {

    /**
     * 服务凭据签发器（《27》§6.2 出站签名）。
     *
     * <p>用 {@code @Autowired(required = false)} 字段注入而非构造器参数：
     * 本类构造器被既有代码/测试直接调用，加参数会连带改无关代码。
     * 未注入或未配置私钥时**自动回退到旧的 X-Internal-Key**，便于灰度与回滚。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.company.cloud.auth.portal.ServiceCredentialSigner serviceSigner;

    /** 自行序列化用：必须拿到与发送完全一致的字节，才能算对 body_sha256。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final PortalProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PortalWalletClient(PortalProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 钱包扣费（契约 §4.3）。幂等键是 {@code bill_no}。
     *
     * @param billNo       本侧账单号（重试必须复用同一个）
     * @param portalUserId 门户用户 UUID
     * @param amountCents  金额（分）
     * @param type         1 续费 / 2 按量
     */
    public PortalApiResponse deduct(String billNo, String portalUserId, long amountCents, int type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bill_no", billNo);
        body.put("portal_user_id", portalUserId);
        // product_code 允许省略，但既然配置了就必须正确（平台侧要求：传了必须等于密钥对应产品）
        body.put("product_code", props.getProductCode());
        body.put("amount_cents", amountCents);
        body.put("type", type);
        return post(props.getWalletDeductPath(), body);
    }

    /**
     * 钱包退款（契约 §4.4）。幂等键是 {@code refund_bill_no}。
     *
     * <p>请求体严格只含契约要求的四个字段：门户按**原单归属**判定权限，
     * 不需要（也不应）由本侧声明 {@code product_code}。
     */
    public PortalApiResponse refund(String originalBillNo, String refundBillNo, long amountCents, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bill_no", originalBillNo);
        body.put("refund_bill_no", refundBillNo);
        body.put("amount_cents", amountCents);
        body.put("reason", reason);
        return post(props.getWalletRefundPath(), body);
    }

    /**
     * 账单对账上报（契约 §6）。幂等：{@code (biz_date, product_code, bill_no)} 唯一，重复上报覆盖金额。
     */
    public PortalApiResponse reportReconciliation(LocalDate bizDate, List<Map<String, Object>> bills) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("biz_date", bizDate.toString());
        body.put("product_code", props.getProductCode());
        body.put("bills", bills);
        return post(props.getReconciliationPath(), body);
    }

    /**
     * 查询用户钱包余额（docs/32）。**只读**，不会为用户创建钱包。
     *
     * <p>返回值里 {@code wallet_exists=false} 是正常状态（用户从未有过资金往来），
     * 不是"用户不存在"；用户不存在门户返回 {@code 1001}。
     *
     * <p>签名要点：这是本类唯一的 **GET + query** 请求，
     * {@code qs} 必须与实际 URL 里的 query **逐字一致**（字典序 + encodeURIComponent），
     * 所以这里先用 {@link ServiceCredentialSigner#canonicalQuery} 算出规范串，
     * 再用**同一串**拼 URL —— 分两次拼必然 1002。
     *
     * @param portalUserId 门户用户 UUID（不是本系统的用户 id）
     */
    public PortalApiResponse getBalance(String portalUserId) {
        // 原始 query（不做排序，交由 canonicalQuery 统一规范化）
        String rawQuery = "portal_user_id=" + portalUserId;
        String qs = com.company.cloud.auth.portal.ServiceCredentialSigner.canonicalQuery(rawQuery);
        String path = props.getWalletBalancePath();

        String url = props.getBaseUrl() + path + "?" + qs;

        String authHeader;
        String authValue;
        if (serviceSigner != null && serviceSigner.isReady()) {
            authHeader = "Authorization";
            // GET 无 body；qs 用同一份规范串
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
            PortalApiResponse parsed = tryParse(e.getResponseBodyAsString());
            if (parsed != null && parsed.code() != 0) {
                log.warn("[portal] 查余额以 HTTP {} 返回业务码 {}（{}）",
                        e.getStatusCode(), parsed.code(), parsed.describe());
                return parsed;
            }
            throw new PortalTransportException(
                    "门户返回 HTTP " + e.getStatusCode() + "（非 {code,...} 信封）", e);
        } catch (RestClientException e) {
            throw new PortalTransportException("门户调用失败: " + e.getMessage(), e);
        }
        return parse(raw, url);
    }
    private PortalApiResponse post(String path, Object body) {
        String url = props.getBaseUrl() + path;

        // ① 自行序列化：body_sha256 必须对**实际发送的字节**计算。
        //    若交给 RestClient 内部序列化，我们拿不到那份字节，hash 必然对不上。
        byte[] bodyBytes = null;
        if (body != null) {
            try {
                bodyBytes = JSON.writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new PortalTransportException("请求体序列化失败: " + e.getMessage(), e);
            }
        }

        // ② 认证头：优先服务凭据（RS256），未配置则回退旧密钥（灰度/回滚用）
        String authHeader;
        String authValue;
        if (serviceSigner != null && serviceSigner.isReady()) {
            authHeader = "Authorization";
            // 出站请求无 query，传 null 即空串
            authValue = "Bearer " + serviceSigner.sign("POST", path, null, bodyBytes);
        } else {
            authHeader = "X-Internal-Key";
            authValue = props.getInternalKey() == null ? "" : props.getInternalKey();
            log.debug("[portal] 服务凭据未启用，回退 X-Internal-Key path={}", path);
        }

        String raw;
        try {
            var spec = restClient.post()
                    .uri(url)
                    .header(authHeader, authValue)
                    .contentType(MediaType.APPLICATION_JSON);
            if (bodyBytes != null) {
                spec = spec.body(bodyBytes);
            }
            raw = spec.retrieve().body(String.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 门户可能用非 2xx 状态码承载业务码（例如 401 + {"code":1002}）。
            // 只要响应体是规范的 {code,...} 信封，就按业务响应返回给上层分类——
            // 否则 1002/1004 这类"重试无用"的码会被当成传输失败白白退避 3 次。
            String errBody = e.getResponseBodyAsString();
            PortalApiResponse parsed = tryParse(errBody);
            if (parsed != null && parsed.code() != 0) {
                log.warn("[portal] 门户以 HTTP {} 返回业务码 {}（{}）—— 按业务响应处理",
                        e.getStatusCode(), parsed.code(), parsed.describe());
                return parsed;
            }
            throw new PortalTransportException(
                    "门户返回 HTTP " + e.getStatusCode() + "（响应体非 {code,...} 信封，按系统失败重试）: "
                            + abbreviate(errBody), e);
        } catch (RestClientException e) {
            // 连接失败 / 超时 / 响应不可解析：系统类失败，可重试
            throw new PortalTransportException("门户调用失败: " + e.getMessage(), e);
        }
        return parse(raw, url);
    }

    private PortalApiResponse parse(String raw, String url) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
            int code = root.path("code").asInt(-1);
            String message = root.path("message").asText("");
            JsonNode data = root.get("data");
            return new PortalApiResponse(code, message, data);
        } catch (Exception e) {
            throw new PortalTransportException("门户响应解析失败 url=" + url + " body=" + abbreviate(raw), e);
        }
    }

    /** 宽松解析：失败返回 null（用于判断非 2xx 响应体里有没有业务码）。 */
    private PortalApiResponse tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parse(raw, "-");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...(truncated)";
    }
}
