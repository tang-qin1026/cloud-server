package com.company.cloud.portal.client;

/**
 * 门户调用**系统类失败**（网络超时 / 连接失败 / 5xx）。
 *
 * <p>与业务失败（{@link PortalApiResponse#businessFailure()}）严格区分：
 * 系统类失败要**用同一个 bill_no 重试**，业务失败**不重试**（契约 §4.3）。
 */
public class PortalTransportException extends RuntimeException {

    public PortalTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
