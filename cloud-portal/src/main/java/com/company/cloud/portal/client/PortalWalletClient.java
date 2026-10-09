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

    private PortalApiResponse post(String path, Object body) {
        String url = props.getBaseUrl() + path;
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .header("X-Internal-Key", props.getInternalKey() == null ? "" : props.getInternalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
