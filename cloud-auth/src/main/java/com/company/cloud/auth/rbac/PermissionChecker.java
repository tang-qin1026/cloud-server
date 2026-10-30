package com.company.cloud.auth.rbac;

import com.company.cloud.auth.rbac.mapper.RbacQueryMapper;
import com.company.cloud.auth.security.CurrentUser;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限点校验器（可跨模块复用）。
 *
 * <p>把 {@code OrgService} 里私有的 {@code requirePerm} 抽出来，供其它模块
 * （如 cloud-files 的管理端大盘）统一判定，避免各模块自己写一份、口径漂移。
 *
 * <p><b>判定口径与 {@code RbacService.effectivePermissions} 一致</b>：
 * <ul>
 *   <li>平台管理员（{@code role=admin}）→ 全平台，直接放行</li>
 *   <li>企业管理员（{@code enterprise_role=1}）→ {@link EnterpriseAdminPermissions#CODES} ∪ 角色权限</li>
 *   <li>其余 → 角色权限并集</li>
 * </ul>
 *
 * <p>⚠️ 企业管理员**不能**简单等同于"有全部权限"：那会连带授予
 * {@code release_sys} / {@code admin_user} / {@code admin_billing}
 * 等平台级权限（详见 {@link EnterpriseAdminPermissions}）。
 */
@Component
@RequiredArgsConstructor
public class PermissionChecker {

    private final RbacQueryMapper rbacMapper;

    /** 该用户的有效权限点集合。 */
    public Set<String> effective(CurrentUser cu) {
        if (cu == null) {
            return Set.of();
        }
        if (cu.isAdmin()) {
            return new LinkedHashSet<>(rbacMapper.selectAllPermCodes());
        }
        // 个人域用户（enterprise_id = 0）：没有企业、没有角色，权限无从配置。
        // 给全部**用户端**权限（14 项），**不含任何管理端权限**。
        // 与 OrgService.effectivePermissions 必须保持一致 —— 两处都返回给前端不同入口，
        // 只改一处会出现"菜单能看见、接口却 40301"的割裂。
        if (cu.isPersonal()) {
            return new LinkedHashSet<>(EnterpriseAdminPermissions.USER_SIDE_CODES);
        }
        LinkedHashSet<String> perms = new LinkedHashSet<>(
                cu.getId() == null ? java.util.List.of() : rbacMapper.selectPermCodes(cu.getId()));
        if (cu.isEnterpriseAdmin()) {
            perms.addAll(EnterpriseAdminPermissions.CODES);
        }
        return perms;
    }

    /** 是否拥有该权限点（平台管理员恒为 true）。 */
    public boolean has(CurrentUser cu, String code) {
        if (cu == null || code == null) {
            return false;
        }
        if (cu.isAdmin()) {
            return true;
        }
        return effective(cu).contains(code);
    }

    /** 要求拥有该权限点，否则 40301。 */
    public void require(CurrentUser cu, String code) {
        if (!has(cu, code)) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少权限：" + code);
        }
    }

    /**
     * 数据范围：是否只能看本企业。
     *
     * <p>用于大盘/审计这类"平台级聚合"接口 —— 企业管理员可以调，但**必须**按
     * 自己企业过滤，否则会把别家企业的数据一起返回。
     */
    public static boolean scopedToEnterprise(CurrentUser cu) {
        return cu != null && !cu.isAdmin() && cu.getEnterpriseId() != null && cu.getEnterpriseId() > 0L;
    }

    /** 数据范围对应的企业 id；返回 {@code null} 表示"不限制"（平台管理员）。 */
    public static Long dataScopeEnterpriseId(CurrentUser cu) {
        return scopedToEnterprise(cu) ? cu.getEnterpriseId() : null;
    }
}
