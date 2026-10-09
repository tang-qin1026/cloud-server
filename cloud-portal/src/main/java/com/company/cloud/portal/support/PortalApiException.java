package com.company.cloud.portal.support;

/**
 * 门户开通接口的业务异常：携带**门户契约要求的 code**（4003 / 4004 / 5001）。
 *
 * <p>刻意继承 RuntimeException 且不用 cloud-common 的 BizException：
 * BizException 的码值来自 ErrorCode 枚举（本系统 4xxxx 段位），而门户契约的
 * 4003/4004/5001 是**门户自己的码空间**，两者不能混用。
 *
 * <p>控制器捕获后返回 {@code Result.error(code, message)}，HTTP 状态码保持 200
 * （契约 §9：门户要求一律 HTTP 200，POST 默认 201 会让门户判错）。
 */
public class PortalApiException extends RuntimeException {

    private final int code;

    public PortalApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** sku 不存在 / 已下架 —— 门户不重试，立即退款。 */
    public static PortalApiException skuNotAvailable(String skuId) {
        return new PortalApiException(4003, "sku 不存在或已下架: " + skuId);
    }

    /** 库存不足 —— 门户不重试，立即退款。 */
    public static PortalApiException outOfStock(String skuId) {
        return new PortalApiException(4004, "库存不足: " + skuId);
    }

    /** 内部错误（资源不足等）—— 门户会重试（最多 3 次），用尽后自动退款。 */
    public static PortalApiException internalError(String message) {
        return new PortalApiException(5001, message);
    }
}
