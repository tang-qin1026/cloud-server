package com.company.cloud.auth.portal;

import com.company.cloud.auth.entity.PortalUserMap;
import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.repository.PortalUserMapRepository;
import com.company.cloud.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 影子用户建档（事务边界独立，便于上层捕获唯一键冲突后回查）。
 *
 * <p>必须与调用方分离：调用方要靠 {@code DataIntegrityViolationException} 判断
 * 「并发首达输给了别人」，若事务方法自调用则代理不生效、异常形态也不对。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalShadowUserCreator {

    private final PortalUserMapRepository mapRepository;
    private final UserRepository userRepository;
    private final PortalAuthProperties props;

    /**
     * 建本地用户 + 写映射（同一事务）。已存在则直接返回既有 local_user_id。
     *
     * @return 本地 users.id
     */
    @Transactional
    public Long create(String portalUserId, String uname) {
        PortalUserMap existing = mapRepository.findById(portalUserId).orElse(null);
        if (existing != null) {
            return existing.getLocalUserId();
        }

        User user = User.builder()
                .username(uniqueUsername(portalUserId))
                .passwordHash(props.getShadowPasswordHash())
                .role("user")
                .quotaBytes(props.getShadowDefaultQuotaBytes())
                .usedBytes(0L)
                .status("active")
                // 影子用户不走本系统登录入口，无需强制改密
                .mustChangePassword(false)
                .extraBytes(0L)
                .build();
        user = userRepository.saveAndFlush(user);

        mapRepository.saveAndFlush(PortalUserMap.builder()
                .portalUserId(portalUserId)
                .localUserId(user.getId())
                .build());

        log.info("[portal] 影子用户建档 portalUserId={} localUserId={} username={} uname={}",
                portalUserId, user.getId(), user.getUsername(), uname);
        return user.getId();
    }

    /**
     * 影子用户名 = 前缀 + 门户 UUID（门户 UUID 唯一 ⇒ 用户名天然唯一）。
     * 极端情况下若仍冲突（例如历史遗留占名），追加序号。
     */
    private String uniqueUsername(String portalUserId) {
        String base = props.getShadowUsernamePrefix() + portalUserId;
        if (!userRepository.existsByUsername(base)) {
            return base;
        }
        for (int i = 2; i <= 20; i++) {
            String candidate = base + "_" + i;
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法为门户用户分配唯一影子用户名: " + portalUserId);
    }
}
