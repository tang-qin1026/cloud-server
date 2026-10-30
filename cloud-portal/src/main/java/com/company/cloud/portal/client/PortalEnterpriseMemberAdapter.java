package com.company.cloud.portal.client;

import com.company.cloud.auth.portal.EnterpriseMemberSyncPort;
import com.company.cloud.auth.portal.PortalShadowUserService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「企业成员同步」端口的实现（cloud-portal 侧）。
 *
 * <p>从门户拉某企业的全部成员（{@code /api/internal/enterprise-accounts}），
 * 再用 {@link PortalShadowUserService} 逐条补建影子用户 —— 与"用户登录时建档"
 * 走**同一个方法**，保证 `ent / er / data_scope` 的投影规则完全一致。
 *
 * <p>企业管理员打开成员列表时由 {@code OrgService} 触发，这样"从没登录过的员工"
 * 也能出现在列表里（否则本地只有登录过的那几个人）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalEnterpriseMemberAdapter implements EnterpriseMemberSyncPort {

    /** 单次同步上限：门户 limit 上限 200，够用；超出说明企业规模异常，先拉前 200。 */
    private static final int PAGE_LIMIT = 200;

    private final PortalDirectoryClient directoryClient;
    private final PortalShadowUserService shadowUserService;

    @Override
    public int syncMembers(long enterpriseId) {
        PortalApiResponse r = directoryClient.listEnterpriseAccounts(
                String.valueOf(enterpriseId), PAGE_LIMIT, 0);
        if (!r.success()) {
            // 门户明确说"企业未激活"（2007）时不是故障，直接返回 0，避免页面报错
            if (r.code() == 2007) {
                log.info("[members-sync] 企业未激活，跳过同步 enterpriseId={}", enterpriseId);
                return 0;
            }
            throw new IllegalStateException(
                    "门户成员清单拉取失败 code=" + r.code() + " msg=" + r.message());
        }
        JsonNode data = r.data();
        JsonNode list = data == null ? null : data.get("list");
        if (list == null || !list.isArray()) {
            return 0;
        }

        int synced = 0;
        for (JsonNode m : list) {
            String portalUserId = text(m, "portal_user_id");
            if (portalUserId == null || portalUserId.isBlank()) {
                continue;
            }
            String username = text(m, "username");
            Integer userType = m.hasNonNull("user_type") ? m.get("user_type").asInt() : null;
            Integer enterpriseRole =
                    m.hasNonNull("enterprise_role") ? m.get("enterprise_role").asInt() : null;
            try {
                // 复用登录建档的同一套投影逻辑：会写 users.enterprise_id / enterprise_role /
                // data_scope（企业管理员提升为 all），并维护 portal_user_map
                shadowUserService.resolveOrCreate(
                        portalUserId, username, userType, enterpriseId, enterpriseRole);
                synced++;
            } catch (Exception e) {
                // 单个成员失败不影响整页：记日志继续
                log.warn("[members-sync] 成员建档失败 enterpriseId={} portalUserId={} err={}",
                        enterpriseId, portalUserId, e.toString());
            }
        }
        log.info("[members-sync] 企业成员同步完成 enterpriseId={} 总数={} 成功={}",
                enterpriseId, list.size(), synced);
        return synced;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
    /**
     * 取门户企业的真实 code / name。
     *
     * <p>用「列企业」接口 + 按 id 过滤：门户没有「按 id 查单个企业」的内网接口，
     * 企业总数是几十的量级，拉一页即可。
     */
    @Override
    public PortalEnterpriseProfile fetchEnterpriseProfile(long enterpriseId) {
        PortalApiResponse r = directoryClient.listEnterprises(null, null, PAGE_LIMIT, 0);
        if (!r.success() || r.data() == null) {
            log.warn("[enterprise-profile] 拉企业目录失败 enterpriseId={} code={}",
                    enterpriseId, r.code());
            return null;
        }
        JsonNode list = r.data().get("list");
        if (list == null || !list.isArray()) {
            return null;
        }
        String want = String.valueOf(enterpriseId);
        for (JsonNode e : list) {
            JsonNode idNode = e.get("enterprise_id");
            if (idNode != null && want.equals(idNode.asText())) {
                String code = text(e, "code");
                String name = text(e, "name");
                if (name == null || name.isBlank()) {
                    return null;
                }
                log.info("[enterprise-profile] 取到门户企业 enterpriseId={} code={} name={}",
                        enterpriseId, code, name);
                return new PortalEnterpriseProfile(code, name);
            }
        }
        return null;
    }
}