package com.company.cloud.auth.portal;

/**
 * 登录回落门户失败。
 *
 * <p>三种原因**必须**由调用方区分处理，其中 {@link Reason#UNAVAILABLE} 最容易被写错：
 * 门户故障**绝不能**当成"密码错"，否则门户一挂，全站用户都会看到"账号或密码错误"，
 * 然后开始怀疑自己密码——排查成本极高。
 */
public class PortalLoginException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** 门户明确回复 2003：账号或密码错误（门户不区分"用户不存在"，我们也不猜） */
        BAD_CREDENTIALS,
        /** 门户明确回复 2004：账号已被禁用 */
        DISABLED,
        /** 网络不可达 / 超时 / 门户 5xx / 未预期 code —— 属服务故障，可重试 */
        UNAVAILABLE
    }

    private final Reason reason;

    public PortalLoginException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PortalLoginException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
