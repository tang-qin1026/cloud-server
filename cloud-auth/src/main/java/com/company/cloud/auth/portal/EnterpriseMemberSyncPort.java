package com.company.cloud.auth.portal;

/**
 * 「企业成员同步」端口（SPI）。
 *
 * <p><b>为什么要有这个接口</b>：企业管理员在成员列表页需要看到**本企业全部员工**，
 * 但门户注册的员工在我们本地只是"登录过才有影子行" —— 没登录的永远看不到。
 * 所以列成员前要先从门户拉一次，把缺失的影子用户补出来。
 *
 * <p>拉门户的代码在 {@code cloud-portal}（它有 {@code PortalDirectoryClient}），
 * 而调用方 {@code OrgService} 在 {@code cloud-auth} —— 后者不能反向依赖前者。
 * 因此这里只定义端口，实现由 cloud-portal 提供，Spring 运行时注入。
 */
public interface EnterpriseMemberSyncPort {

    /**
     * 从门户拉取某企业的成员，并确保本地存在对应影子用户。
     *
     * @param enterpriseId 门户的企业 id
     * @return 实际同步的成员条数；门户不可用时应抛出异常，由调用方决定是否降级
     */
    int syncMembers(long enterpriseId);

    /**
     * 从门户取某企业的真实 code / name。
     *
     * <p>为什么需要：影子企业建档时只有占位名（{@code 企业 #7}），
     * 因为门户用户首次登录时我们只拿到 enterprise_id，没有企业名称。
     * 结果「企业详情」里显示「企业 #7」而不是「长荣饭店」。
     *
     * @return 门户里的 code/name；门户不可用或查不到时返回 {@code null}
     */
    PortalEnterpriseProfile fetchEnterpriseProfile(long enterpriseId);

    /** 门户企业的展示信息。 */
    record PortalEnterpriseProfile(String code, String name) {
    }
}