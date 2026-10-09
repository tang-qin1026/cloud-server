package com.company.cloud.portal.client;

import com.company.cloud.portal.config.PortalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门户内网接口客户端（契约 §4.3 / §4.4 / §6）。
 *
 * <p>统一带 {@code X-Internal-Key}；系统类失败抛出 {@link PortalTransportException}
 * 供上层按「同一 bill_no 退避重试」处理。
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
        body.put("product_code", props.getProductCode());
        body.put("amount_cents", amountCents);
        body.put("type", type);
        return post(props.getWalletDeductPath(), body);
    }

    /**
     * 钱包退款（契约 §4.4）。幂等键是 {@code refund_bill_no}。
     */
    public PortalApiResponse refund(String originalBillNo, String refundBillNo, long amountCents,
                                    String reason, String portalUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bill_no", originalBillNo);
        body.put("refund_bill_no", refundBillNo);
        body.put("amount_cents", amountCents);
        body.put("reason", reason);
        // 附加字段便于门户侧溯源（门户按 bill_no 查扣费链路）
        body.put("portal_user_id", portalUserId);
        body.put("product_code", props.getProductCode());
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
        } catch (HttpClientErrorException e) {
            // 4xx：内网密钥错误 / 路径错误 / 契约不符，属配置问题，重试无意义
            throw new PortalTransportException(
                    "门户返回 4xx（请检查 X-Internal-Key 与接口路径）: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // 连接失败 / 超时 / 5xx：系统类失败，可重试
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
            throw new PortalTransportException("门户响应解析失败 url=" + url + " body=" + raw, e);
        }
    }
}
