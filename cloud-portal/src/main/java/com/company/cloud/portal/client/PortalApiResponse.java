package com.company.cloud.portal.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 门户内部接口统一响应体 {@code {code, message, data}}。
 *
 * <p>已知码（契约 §4.3 / §4.4 + 平台侧 2026-10-09 补充）：
 * <table border="1">
 *   <caption>门户码分类</caption>
 *   <tr><th>code</th><th>含义</th><th>本侧处理</th></tr>
 *   <tr><td>0</td><td>成功</td><td>入账</td></tr>
 *   <tr><td>3003</td><td>幂等重复</td><td>**当成功**，取首次结果</td></tr>
 *   <tr><td>1002</td><td>密钥无效（没配/配错）</td><td>**不重试**，告警（配置错误）</td></tr>
 *   <tr><td>1004</td><td>密钥与账单/声明不匹配</td><td>**不重试**，告警（代码 bug，重试无用）</td></tr>
 *   <tr><td>3001</td><td>余额不足</td><td>**不重试**</td></tr>
 *   <tr><td>3002</td><td>钱包冻结</td><td>**不重试**</td></tr>
 *   <tr><td>3004</td><td>累计退款超过原单金额</td><td>**不重试**（data.refundable_cents 告知还能退多少）</td></tr>
 *   <tr><td>3005</td><td>原单不存在</td><td>**不重试**</td></tr>
 *   <tr><td>&gt;= 5000</td><td>门户系统错误</td><td>**可重试**（与网络失败同等对待）</td></tr>
 * </table>
 *
 * <p><b>分类原则</b>：只有 {@code >= 5000} 的自报系统错误才退避重试；所有 1xxx/3xxx 业务码
 * 一律**不重试**。这条规则对应平台侧明确提出的要求——{@code 1002}/{@code 1004} 重试毫无意义，
 * 当成系统失败白退避 3 次（1s/5s/30s）只会拖慢告警。
 */
public record PortalApiResponse(int code, String message, JsonNode data) {

    /** 门户自报系统错误的下界。 */
    private static final int SYSTEM_FAILURE_FLOOR = 5000;

    /** 成功：{@code 0} 正常；{@code 3003} 幂等重复，语义等价于成功。 */
    public boolean success() {
        return code == 0 || code == 3003;
    }

    /**
     * 是否**可重试**的门户系统错误（{@code >= 5000}）。
     *
     * <p>与网络/5xx 传输失败同等对待：用同一个 bill_no 退避重试。
     */
    public boolean retryableSystemFailure() {
        return code >= SYSTEM_FAILURE_FLOOR;
    }

    /** 是否业务失败（**不重试**）：一切 1xxx / 3xxx 码。 */
    public boolean businessFailure() {
        return code > 0 && code < SYSTEM_FAILURE_FLOOR;
    }

    /** 密钥类错误（1002/1004）：属配置错误或代码 bug，需要告警而非重试。 */
    public boolean credentialFailure() {
        return code == 1002 || code == 1004;
    }

    /** 供日志用的中文释义。 */
    public String describe() {
        return switch (code) {
            case 0 -> "成功";
            case 1002 -> "密钥无效（没配/配错）—— 配置错误，不重试";
            case 1004 -> "密钥与账单/声明不匹配 —— 代码 bug，重试无用";
            case 3001 -> "余额不足";
            case 3002 -> "钱包冻结";
            case 3003 -> "幂等重复（当成功）";
            case 3004 -> "累计退款超过原单金额";
            case 3005 -> "原单不存在";
            default -> code >= SYSTEM_FAILURE_FLOOR ? "门户系统错误" : "未预期业务码";
        };
    }
}
