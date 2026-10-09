package com.company.cloud.portal.service;

import com.company.cloud.portal.config.PortalProperties;
import com.company.cloud.portal.entity.PortalProvision;
import com.company.cloud.portal.mapper.PortalProvisionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订阅状态机 · 到期 → 宽限 → 停机（契约 §8 自检项「业务状态机（你自己实现）」）。
 *
 * <p>两步都建立在**条件更新**上，因此天然幂等、可重入、可并发：
 * <ol>
 *   <li>{@code active → grace}：批量条件更新，同时算出 {@code grace_end_at = expire_at + grace_days}</li>
 *   <li>{@code grace → expired}：逐条条件更新，<b>只有把状态改成 expired 的那一次调用</b>
 *       才去回收配额（{@code users.quota_bytes -= granted_bytes}）。
 *       条件更新保证同一记录只有一方返回 1，所以配额不会被重复扣。</li>
 * </ol>
 *
 * <p>停机只回收配额，不删用户、不删文件：用户已上传的文件仍在，只是无法再上传新内容，
 * 从而留出人工介入（续费后重新开通即可恢复额度）的窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalSubscriptionExpireTask {

    private final PortalProvisionMapper provisionMapper;
    private final PortalProperties props;

    @Scheduled(cron = "${app.portal.expire-cron:0 0 4 * * *}")
    public void run() {
        OffsetDateTime now = OffsetDateTime.now();
        int graceDays = props.getSubscribeGraceDays();

        // ① 到期 → 宽限
        int toGrace = provisionMapper.markDueAsGrace(now, graceDays);
        log.info("[portal] 订阅到期扫描 now={} 转入宽限 {} 条（宽限 {} 天）", now, toGrace, graceDays);

        // ② 宽限结束 → 停机 + 回收配额
        List<PortalProvision> dueList = provisionMapper.selectGraceDue(now);
        int stopped = 0;
        for (PortalProvision p : dueList) {
            try {
                // 条件更新：只有把 grace 改成 expired 的这一次才回收配额
                if (provisionMapper.markExpired(p.getId()) > 0) {
                    if (p.getGrantedBytes() != null && p.getGrantedBytes() > 0) {
                        provisionMapper.reclaimQuota(p.getLocalUserId(), p.getGrantedBytes());
                    }
                    stopped++;
                    log.info("[portal] 订阅停机 orderItemId={} localUserId={} 回收配额 {} 字节",
                            p.getOrderItemId(), p.getLocalUserId(), p.getGrantedBytes());
                }
            } catch (Exception e) {
                log.error("[portal] 订阅停机失败 id={} orderItemId={}", p.getId(), p.getOrderItemId(), e);
            }
        }
        log.info("[portal] 订阅停机完成，本次停机 {} 条", stopped);
    }
}
