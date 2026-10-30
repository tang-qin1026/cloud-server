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
    /** 影子企业 upsert：本地没有门户企业行时补一行。 */
    private final com.company.cloud.auth.org.mapper.OrgWriteMapper orgWriteMapper;
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
    /**
     * 门户令牌带租户信息时的解析：除影子用户外，还把 {@code ut / ent / er}
     * **投影**到本地 {@code users}（企业归属的权威在门户，产品侧只做投影）。
     *
     * <p>只在值发生变化时写库，避免每个请求都 UPDATE。
     */
    @jakarta.transaction.Transactional
    public CurrentUser resolveOrCreate(String portalUserId, String uname,
                                       Integer userType, Long enterpriseId, Integer enterpriseRole) {
        // 门户契约：ut=1（企业用户）必须带 ent。缺了就拒绝，**不能退化成个人域**。
        // 过滤器层已挡一次；这里再挡一次，因为成员同步等路径也会调到本方法，
        // 安全约束不能只靠调用方守规矩。
        if (userType != null && userType == 1 && (enterpriseId == null || enterpriseId <= 0L)) {
            throw new com.company.cloud.common.result.BizException(
                    com.company.cloud.common.result.ErrorCode.UNAUTHORIZED,
                    "企业用户的门户令牌缺少企业归属（ut=1 但无 ent），已拒绝");
        }
        CurrentUser base = resolveOrCreate(portalUserId, uname);
        if (enterpriseId == null && enterpriseRole == null) {
            return base;
        }
        User user = userRepository.findById(base.getId()).orElse(null);
        if (user == null) {
            return base;
        }
        boolean changed = false;
        if (enterpriseId != null && !enterpriseId.equals(user.getEnterpriseId())) {
            user.setEnterpriseId(enterpriseId);
            changed = true;
        }
        if (enterpriseRole != null
                && !java.util.Objects.equals(enterpriseRole.shortValue(), user.getEnterpriseRole())) {
            user.setEnterpriseRole(enterpriseRole.shortValue());
            changed = true;
        }
        // 影子企业：本地没有这行的话，/admin/login 查不到企业状态会 fail-closed，
        // 门户的企业管理员就永远进不了管理端。这里按门户的 ent 建/补一行。
        // 回填 nickname：存量影子用户的 nickname 为空（建档时没写），
        // 借登录/同步时把门户真实用户名补上，前端就不用再显示 portal_<uuid>。
        if (uname != null && !uname.isBlank()
                && (user.getNickname() == null || user.getNickname().isBlank())) {
            user.setNickname(uname.trim());
            changed = true;
        }
        // 企业管理员默认 data_scope = all（本企业内全权）。
        //
        // 不这么做的话，影子用户建出来是实体默认值 self，
        // 结果企业管理员打开成员列表只能看到自己 —— 看起来像"员工列表坏了"。
        // 数据范围的**上限**仍由 enterprise_id 兜住（跨企业依然看不到）。
        // ⚠️ 必须用 getDataScopeRaw()：User.getDataScope() 是重写过的 getter，
        // 返回的是**对外大写档位名**（SELF/DEPT/...），拿它跟 "self" 比较永远为假。
        String rawScope = user.getDataScopeRaw();
        if (enterpriseRole != null && enterpriseRole == 1
                && (rawScope == null || "self".equals(rawScope))) {
            user.setDataScope("all");
            changed = true;
            log.info("[portal] 企业管理员 data_scope 提升为 all userId={} enterpriseId={}",
                    user.getId(), enterpriseId);
        }
        if (enterpriseId != null) {
            Long owner = (enterpriseRole != null && enterpriseRole == 1) ? user.getId() : null;
            orgWriteMapper.upsertPortalEnterprise(
                    enterpriseId, "portal-" + enterpriseId, "企业 #" + enterpriseId, owner);
        }
        if (changed) {
            userRepository.save(user);
            log.info("[portal] 投影租户信息 userId={} enterpriseId={} enterpriseRole={}",
                    user.getId(), user.getEnterpriseId(), user.getEnterpriseRole());
        }
        return new CurrentUser(
                user.getId(), user.getUsername(), user.getRole(),
                userType == null ? 0 : userType,
                user.getEnterpriseId(),
                user.getEnterpriseRole() == null ? 0 : user.getEnterpriseRole().intValue());
    }
}