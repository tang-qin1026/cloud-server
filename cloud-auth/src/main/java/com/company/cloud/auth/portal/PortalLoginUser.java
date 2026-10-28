package com.company.cloud.auth.portal;

/**
 * 门户登录成功后我们需要的用户信息。
 *
 * <p>取 {@code data.user} 下的：{@code id}（门户 UUID）、{@code username}，
 * 以及 v2 新增的租户三件套 {@code user_type} / {@code enterprise_id} / {@code enterprise_role}。
 *
 * <p><b>为什么必须取后三个</b>：只取 id 会让影子用户永远落成
 * {@code enterprise_id = 0}、{@code enterprise_role = 0} ——
 * 门户注册的企业管理员登录后 {@code /me/context} 返回 {@code isEnterpriseAdmin=false}，
 * 前端据此判定"不是管理员"，表现为**企业管理员登录不了管理端**（真实缺陷）。
 *
 * <p>门户返回的 {@code role}（user/admin）与 {@code access_token} 仍然**不用**：
 * 前者是门户语义，后者我们只借门户做密码判定，签发仍走本系统 HS256。
 *
 * @param portalUserId   门户用户 UUID（36 位），映射表的 portal_user_id
 * @param username       门户用户名，建影子用户时留痕
 * @param userType       {@code user_type}：0 个人 / 1 企业员工；可空（兼容旧门户）
 * @param enterpriseId   {@code enterprise_id}：所属企业；个人用户为 null
 * @param enterpriseRole {@code enterprise_role}：0 普通成员 / 1 企业管理员；可空
 */
public record PortalLoginUser(String portalUserId, String username,
                              Integer userType, Long enterpriseId, Integer enterpriseRole) {

    /** 兼容两参构造（旧调用点/测试）。 */
    public PortalLoginUser(String portalUserId, String username) {
        this(portalUserId, username, null, null, null);
    }
}