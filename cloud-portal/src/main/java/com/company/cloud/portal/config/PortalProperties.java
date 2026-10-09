package com.company.cloud.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 门户接入配置（app.portal.*）。
 *
 * <p>与 {@code app.portal.auth.*}（认证侧，cloud-auth 的 PortalAuthProperties）分开：
 * 本类只管产品侧调用门户所需的东西（内网密钥、门户地址、出账/对账策略）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.portal")
public class PortalProperties {

    /** 本产品在门户侧的产品编码，由门户配置决定：storage / phone / desktop。 */
    private String productCode = "storage";

    /** 门户内网地址（不带尾部斜杠），例如 http://192.168.99.1:3000 。 */
    private String baseUrl = "http://localhost:3000";

    /**
     * 门户 → 本产品 的内部调用密钥（X-Internal-Key）。
     * 由平台侧下发；**留空视为未配置，内网接口一律拒绝**（fail-closed）。
     */
    private String internalKey = "";

    /** 钱包扣费路径（相对 baseUrl）。 */
    private String walletDeductPath = "/api/internal/wallet/deduct";

    /** 钱包退款路径。 */
    private String walletRefundPath = "/api/internal/wallet/refund";

    /** 账单对账上报路径（契约 §6 明确为 /api/internal/reconciliation/report）。 */
    private String reconciliationPath = "/api/internal/reconciliation/report";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 10000;

    /**
     * 系统类失败（网络超时 / 5xx）的退避重试间隔（毫秒），契约 §4.3 建议 1s / 5s / 30s。
     * 共 3 次重试 ⇒ 最多 4 次请求。业务类失败（3001/3002）**不重试**。
     */
    private List<Integer> deductRetryDelaysMs = new ArrayList<>(List.of(1000, 5000, 30000));

    /** 到期后的宽限天数（契约自检项：到期 → 宽限 → 停机，建议 7 天）。 */
    private int subscribeGraceDays = 7;

    /** 订阅到期扫描 cron（默认每日 04:00）。 */
    private String expireCron = "0 0 4 * * *";

    /** 每日账单上报 cron（默认每日 06:00，上报前一日）。 */
    private String reconcileCron = "0 0 6 * * *";

    /** 对账上报的系统类失败重试次数（对账幂等，可安全重试）。 */
    private int reconcileRetries = 3;

    /** 按量计费（billing_mode=2）后付费出账开关，默认关闭，需人工确认计费口径后再开。 */
    private Postpaid postpaid = new Postpaid();

    @Data
    public static class Postpaid {
        /** 是否启用按量后付出账任务。 */
        private boolean enabled = false;
        /** 出账任务 cron（默认每日 05:30）。 */
        private String cron = "0 30 5 * * *";
        /** 单次任务最多出账条数，防止一次性打爆门户钱包。 */
        private int batchSize = 200;
    }
}
