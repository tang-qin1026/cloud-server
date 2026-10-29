package com.company.cloud.auth.rbac;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 企业管理员的**默认权限点**（产品口径，2026-10-28 确认）。
 *
 * <p><b>为什么需要这个类</b>：改造前企业管理员被当成"全量权限"处理
 * （{@code selectAllPermCodes()}），等于把平台级权限也给了它：
 * <ul>
 *   <li>{@code admin.release.manage} —— 能发全平台的 App 版本</li>
 *   <li>{@code sys.config.manage} / {@code sys.secret.manage} —— 能改系统配置、读内网密钥</li>
 *   <li>{@code admin.user.manage} —— 能管所有企业的用户</li>
 *   <li>{@code admin.billing.*} / {@code admin.ledger.*} —— 能看全平台台账与增额审批</li>
 * </ul>
 * 这是越权：任一企业的管理员都能影响全平台。
 *
 * <p><b>口径</b>：企业管理员 = 本企业内的"全权"，但不是平台权。
 * 因此授予 {@code file.*} / {@code recycle.*} / {@code transfer.*} / {@code quota.*} /
 * {@code org.*} / {@code role.*} / {@code admin.audit.*} / {@code admin.dashboard.*}，
 * **不授予** {@code admin_billing} / {@code admin_user} / {@code release_sys} 三组。
 *
 * <p>与角色权限的关系：企业管理员的有效权限 = 本集合 ∪ 其角色已授予的权限
 * （角色可能被额外授予某些权限，取并集更符合直觉）。
 */
public final class EnterpriseAdminPermissions {

    /**
     * 企业管理员的默认权限点（26 项）。
     *
     * <p>用 {@link LinkedHashSet} 而非 {@code Set.of}：前者保证返回顺序稳定，
     * 便于前端菜单渲染与人工核对；{@code Set.of} 的迭代顺序未定义。
     */
    public static final Set<String> CODES = java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    // 文件
                    "file.read",
                    "file.upload",
                    "file.modify",
                    "file.delete",
                    "file.other.read",
                    "file.other.delete",
                    // 回收站
                    "recycle.read",
                    "recycle.restore",
                    "recycle.purge",
                    // 传输
                    "transfer.read",
                    "transfer.control",
                    // 配额与计费（本企业口径）
                    "quota.read",
                    "billing.read",
                    "increase.apply",
                    "increase.read",
                    // 组织
                    "org.dept.read",
                    "org.dept.manage",
                    "org.member.read",
                    "org.member.manage",
                    "org.member.resource",
                    // 角色
                    "role.read",
                    "role.manage",
                    // 审计与大盘（按本企业数据范围）
                    "admin.audit.read",
                    "admin.audit.export",
                    "admin.dashboard.read",
                    "admin.dashboard.export"
            )));

    /**
     * 明确**不授予**企业管理员的权限分组（平台级）。
     *
     * <p>留这个常量是为了让"为什么不给"可被代码检索到，也便于测试断言
     * 「企业管理员不得拥有这些」。
     */
    public static final List<String> DENIED_GROUPS =
            List.of("admin_billing", "admin_user", "release_sys");

    private EnterpriseAdminPermissions() {
    }

    /** 企业管理员是否默认拥有该权限点。 */
    public static boolean grants(String code) {
        return code != null && CODES.contains(code);
    }
    /**
     * 企业管理员**可以下放给员工**的权限点（15 项）。
     *
     * <p>口径：员工只做"自己的事"——文件、回收站、传输、配额查看与增额申请。
     * 组织管理、角色管理、审计与大盘属于**管理员职责**，只能由管理员本人持有，
     * 不能通过角色下放给员工。
     *
     * <p>因此 {@link #CODES}（26 项）− {@link #ASSIGNABLE_BY_ENTERPRISE_ADMIN}（15 项）
     * = 11 项仅管理员自持：{@code org.dept.*} / {@code org.member.*} / {@code role.*} /
     * {@code admin.audit.*} / {@code admin.dashboard.*}。
     */
    public static final Set<String> ASSIGNABLE_BY_ENTERPRISE_ADMIN =
            java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
                    // 文件
                    "file.read",
                    "file.upload",
                    "file.modify",
                    "file.delete",
                    "file.other.read",
                    "file.other.delete",
                    // 回收站
                    "recycle.read",
                    "recycle.restore",
                    "recycle.purge",
                    // 传输
                    "transfer.read",
                    "transfer.control",
                    // 配额与计费（本企业口径）
                    "quota.read",
                    "billing.read",
                    "increase.apply",
                    "increase.read"
            )));

    /** 企业管理员可下放给该权限点吗。 */
    public static boolean canAssignToEmployee(String code) {
        return code != null && ASSIGNABLE_BY_ENTERPRISE_ADMIN.contains(code);
    }
}