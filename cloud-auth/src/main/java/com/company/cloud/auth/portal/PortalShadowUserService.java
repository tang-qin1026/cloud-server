package com.company.cloud.auth.portal;

import com.company.cloud.auth.entity.PortalUserMap;
import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.repository.PortalUserMapRepository;
import com.company.cloud.auth.repository.UserRepository;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 门户影子用户解析 / 首达建档（契约 §4.1）。
 *
 * <p>流程：拿门户 JWT 的 {@code sub} 查 {@code portal_user_map}
 * <ul>
 *   <li>有记录 → 用 {@code local_user_id} 继续</li>
 *   <li>无记录 → 建本地用户 + 写映射（同一事务，见 {@link PortalShadowUserCreator}）</li>
 * </ul>
 *
 * <p><b>并发兜底</b>：两个请求同时首达会各建一个用户，靠唯一索引拦下其中一个，
 * 失败方捕获 {@link DataIntegrityViolationException} 后回查一次即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalShadowUserService {

    private final PortalUserMapRepository mapRepository;
    private final UserRepository userRepository;
    private final PortalShadowUserCreator creator;

    /**
     * 解析门户用户对应的本地身份；不存在则建档。
     *
     * @param portalUserId 门户 JWT 的 sub
     * @param uname        门户 JWT 的 uname（可空）
     * @return 本地身份（id 是本地 users.id，业务代码据此取数据）
     */
    public CurrentUser resolveOrCreate(String portalUserId, String uname) {
        PortalUserMap map = mapRepository.findById(portalUserId).orElse(null);
        if (map == null) {
            try {
                Long localUserId = creator.create(portalUserId, uname);
                map = PortalUserMap.builder()
                        .portalUserId(portalUserId)
                        .localUserId(localUserId)
                        .build();
            } catch (DataIntegrityViolationException e) {
                // 并发首达：唯一索引已拦下本次插入，回查赢家的记录
                map = mapRepository.findById(portalUserId).orElse(null);
                if (map == null) {
                    log.warn("[portal] 影子用户建档冲突且回查不到，portalUserId={}", portalUserId);
                    throw new BizException(ErrorCode.SYSTEM_ERROR, "门户用户建档冲突，请重试");
                }
                log.debug("[portal] 影子用户建档并发冲突，回查命中 portalUserId={} localUserId={}",
                        portalUserId, map.getLocalUserId());
            }
        }

        Optional<User> userOpt = userRepository.findById(map.getLocalUserId());
        User user = userOpt.orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND, "门户映射的本地用户不存在"));
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        return new CurrentUser(user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * 供 cloud-portal 的 provision 使用：只取本地用户 id，不存在则建档。
     */
    public CurrentUser resolveForProvision(String portalUserId) {
        return resolveOrCreate(portalUserId, null);
    }
}
