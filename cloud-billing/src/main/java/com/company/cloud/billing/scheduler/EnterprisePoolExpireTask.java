package com.company.cloud.billing.scheduler;

import com.company.cloud.billing.service.EnterprisePoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 企业池到期推进（docs/31 §7）。
 *
 * <p>每 5 分钟一次：
 * <ul>
 *   <li>active → grace（到期，禁上传但可读）</li>
 *   <li>grace → reclaimed（宽限结束，池扣减，成员回落个人额度）</li>
 *   <li>重算受影响企业的汇总（池缩水会让 over_allocated 变 true）</li>
 * </ul>
 *
 * <p>可用 {@code app.enterprise-pool.schedule-enabled=false} 关掉（单测/离线环境）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.enterprise-pool.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class EnterprisePoolExpireTask {

    private final EnterprisePoolService poolService;

    @Scheduled(fixedDelayString = "${app.enterprise-pool.tick-ms:300000}", initialDelay = 60000)
    public void tick() {
        try {
            poolService.advanceStates(OffsetDateTime.now());
        } catch (Exception e) {
            // 任务失败不能让调度线程死掉
            log.error("[pool] 到期推进失败: {}", e.toString());
        }
    }
}