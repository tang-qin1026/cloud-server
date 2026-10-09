package com.company.cloud.portal.client;

/**
 * 门户内部接口统一响应体 {@code {code, message, data}}。
 *
 * <p>已知业务码（契约 §4.3 / §4.4）：
 * <ul>
 *   <li>{@code 0}    —— 成功</li>
 *   <li>{@code 3001} —— 余额不足（业务失败，**不重试**）</li>
 *   <li>{@code 3002} —— 钱包冻结（业务失败，**不重试**）</li>
 *   <li>{@code 3003} —— 幂等重复（**当成功处理**，取首次结果）</li>
 *   <li>{@code 3004} —— 累计退款超过原单金额（data.refundable_cents 告诉还能退多少）</li>
 *   <li>{@code 3005} —— 原单不存在</li>
 * </ul>
 */
public record PortalApiResponse(int code, String message, com.fasterxml.jackson.databind.JsonNode data) {

    public boolean success() {
        // 3003 = 幂等重复，语义上等价于成功
        return code == 0 || code == 3003;
    }

    /** 是否业务失败（不重试）。 */
    public boolean businessFailure() {
        return code == 3001 || code == 3002;
    }
}
