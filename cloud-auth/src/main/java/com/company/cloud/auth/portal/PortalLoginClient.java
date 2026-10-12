package com.company.cloud.auth.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 门户统一认证客户端：调 {@code POST {PORTAL_BASE_URL}/api/auth/login}。
 *
 * <p>用途：本系统登录时**本地密码验不过**才回落到这里，由门户判定账号密码是否正确。
 * 我们不使用门户返回的 token，只取 {@code data.user.id}（门户用户 UUID）去映射影子用户，
 * 最终签发的仍是本系统的 HS256 令牌。
 *
 * <p>三个关键设计（对应《云存储侧改造说明》§4.1）：
 * <ol>
 *   <li><b>超时显式设置</b>：登录是同步交互，若挂 HTTP 客户端默认值（可能几十秒），
 *       用户会以为页面卡死。默认 3000ms，可用 {@code PORTAL_LOGIN_TIMEOUT_MS} 调整。</li>
 *   <li><b>网络异常 ≠ 密码错</b>：连接失败/超时/未预期 code 一律抛
 *       {@link PortalLoginException.Reason#UNAVAILABLE}，由调用方转成"服务暂不可用"。</li>
 *   <li><b>密码不进日志</b>：请求体 DTO 的 {@code toString()} 显式屏蔽密码；
 *       本类所有日志均不打印请求体。</li>
 * </ol>
 *
 * <p>注意：门户与我们的错误风格不同——门户 **HTTP 恒为 200，业务结果看 body 的 code**；
 * 而我们自己的 {@code /internal/**} 是"401 + 40001"。所以这里只认 code，不看 HTTP 状态码。
 */
@Slf4j
@Component
public class PortalLoginClient {

    private static final String LOGIN_PATH = "/api/auth/login";

    /** 门户错误码（见改造说明 §10）。 */
    private static final int CODE_OK = 0;
    private static final int CODE_PARAM_INVALID = 1001;
    private static final int CODE_BAD_CREDENTIALS = 2003;
    private static final int CODE_ACCOUNT_DISABLED = 2004;
    private static final int CODE_PORTAL_ERROR = 5001;

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PortalLoginClient(@Value("${app.portal.base-url:}") String baseUrl,
                             @Value("${app.portal.login-timeout-ms:3000}") int timeoutMs,
                             ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 调门户校验账号密码。
     *
     * @param username 用户在云存储登录页输入的内容（门户支持用户名或手机号，我们原样透传）
     * @param password 明文密码（仅在内存中传递，不落任何日志）
     * @return 门户用户（含 portal 用户 UUID）
     * @throws PortalLoginException 账号密码错 / 被禁用 / 门户不可用，三类由 {@code reason} 区分
     */
    public PortalLoginUser login(String username, String password) {
        if (baseUrl.isEmpty()) {
            throw new PortalLoginException(PortalLoginException.Reason.UNAVAILABLE,
                    "未配置 app.portal.base-url，无法回落门户登录");
        }
        String url = baseUrl + LOGIN_PATH;
        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PortalLoginBody(username, password))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 连接失败 / 读超时 / 5xx：属服务故障，绝不能当成"密码错"
            throw new PortalLoginException(PortalLoginException.Reason.UNAVAILABLE,
                    "门户登录接口不可达: " + e.getMessage(), e);
        }
        return parse(raw, username);
    }

    private PortalLoginUser parse(String raw, String username) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new PortalLoginException(PortalLoginException.Reason.UNAVAILABLE,
                    "门户登录响应解析失败", e);
        }
        int code = root.path("code").asInt(-1);
        String message = root.path("message").asText("");

        switch (code) {
            case CODE_OK:
                JsonNode user = root.path("data").path("user");
                String id = user.path("id").asText("");
                if (id.isBlank()) {
                    // 门户说成功却没给 UUID：属契约异常，不能建映射，按服务故障处理
                    throw new PortalLoginException(PortalLoginException.Reason.UNAVAILABLE,
                            "门户登录成功但未返回 data.user.id");
                }
                log.debug("[portal] 登录回落门户校验通过 portalUserId={}", id);
                return new PortalLoginUser(id, user.path("username").asText(username));

            case CODE_BAD_CREDENTIALS:
                // 门户不区分"用户不存在"与"密码错"，我们也不猜，原样透传
                throw new PortalLoginException(PortalLoginException.Reason.BAD_CREDENTIALS,
                        message.isEmpty() ? "账号或密码错误" : message);

            case CODE_ACCOUNT_DISABLED:
                throw new PortalLoginException(PortalLoginException.Reason.DISABLED,
                        message.isEmpty() ? "账号已被禁用" : message);

            case CODE_PARAM_INVALID:
            case CODE_PORTAL_ERROR:
            default:
                // 1001/5001 及其它未预期码：都是门户侧问题，与"密码错"严格区分
                throw new PortalLoginException(PortalLoginException.Reason.UNAVAILABLE,
                        "门户返回 code=" + code + " message=" + message);
        }
    }

    /**
     * 门户登录请求体。
     *
     * <p>{@code toString()} 显式屏蔽密码：防止任何意外的日志/调试输出把明文密码打出去。
     */
    private record PortalLoginBody(String username, String password) {
        @Override
        public String toString() {
            return "PortalLoginBody[username=" + username + ", password=***]";
        }
    }
}
