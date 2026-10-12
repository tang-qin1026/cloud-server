package com.company.cloud.auth.portal;

/**
 * 门户登录成功后我们需要的用户信息。
 *
 * <p>只取两个字段：{@code data.user.id}（门户用户 UUID）与 {@code data.user.username}。
 * 其余（access_token / expires_in / role / status）我们**不用**——
 * 我们只借门户做"这组账号密码对不对"的判定，签发仍走本系统的 HS256 令牌。
 *
 * @param portalUserId 门户用户 UUID（36 位），即映射表里的 portal_user_id
 * @param username     门户用户名，仅用于建影子用户时留痕与日志
 */
public record PortalLoginUser(String portalUserId, String username) {
}
