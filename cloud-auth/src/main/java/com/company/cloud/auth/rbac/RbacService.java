package com.company.cloud.auth.rbac;

import com.company.cloud.auth.entity.User;
import com.company.cloud.auth.rbac.mapper.RbacQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RBAC 读取服务：**惰性角色初始化** + **有效功能权限计算**。
 *
 * <p>依据《24 - 企业版权限落地实施文档》v2 §3.3：
 * <ul>
 *   <li>角色是**产品概念**，门户没有存它的地方 → 由云存储在该企业**首次被访问时惰性创建**，
 *       靠唯一键 {@code uk_role_code} 防并发生成两份（§3.3-3）</li>
 *   <li><b>企业管理员不挂角色</b>，直接给全部 40 项（§3.3-4）</li>
 * </ul>
 *
 * <p><b>v2 的判据变更</b>：判"企业管理员"用 {@code users.enterprise_role = 1}，
 * **不再用 {@code enterprise.owner_user_id}**（后者只表示创建者/主管理员，转让时才改）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacService {

    /** 4 个内置角色模板（企业管理员不在其中——它是 {@code enterprise_role=1}，不占角色行）。 */
    private static final List<Template> TEMPLATES = List.of(
            new Template("dept_admin", "部门管理员", new String[]{
                    "file.read", "file.upload", "file.modify", "file.delete",
                    "file.other.read",
                    "recycle.read", "recycle.restore", "recycle.purge",
                    "quota.read", "org.member.read", "admin.dashboard.read"}),
            new Template("member", "成员", new String[]{
                    "file.read", "file.upload", "file.modify", "file.delete",
                    "recycle.read", "recycle.restore", "recycle.purge",
                    "transfer.read", "transfer.control",
                    "quota.read", "billing.read",
                    "increase.apply", "increase.read"}),
            new Template("auditor", "审计员", new String[]{
                    "admin.audit.read"}),
            new Template("finance", "财务审批", new String[]{
                    "admin.billing.read", "admin.increase.read", "admin.increase.approve",
                    "admin.ledger.read", "admin.ledger.export"}));

    private record Template(String code, String name, String[] perms) {
    }

    private final RbacQueryMapper mapper;

    /**
     * 确保该企业已有 4 个内置角色。**幂等**，可并发调用。
     *
     * @return 本次是否执行了初始化（false = 已存在，跳过）
     */
    @Transactional
    public boolean ensureEnterpriseRoles(long enterpriseId) {
        if (mapper.countRoles(enterpriseId) > 0) {
            return false;
        }
        for (Template t : TEMPLATES) {
            // ON CONFLICT DO NOTHING：并发时只有一方真正插入
            mapper.insertRoleIfAbsent(enterpriseId, t.code(), t.name());
            mapper.grantToRole(enterpriseId, t.code(), t.perms());
        }
        log.info("[rbac] 企业 {} 惰性初始化 {} 个内置角色", enterpriseId, TEMPLATES.size());
        return true;
    }

    /**
     * 计算用户的有效功能权限点。
     *
     * <p>优先级：<b>平台管理员</b>（{@code role=admin}）→ 全部权限点；
     * <b>企业管理员</b>（{@code enterprise_role=1}）→ {@link EnterpriseAdminPermissions#CODES}
     * ∪ 角色权限；其余按多角色并集。
     *
     * <p>⚠️ 企业管理员**不再**等同"全量"：那会连带授予平台级权限（见该类注释）。
     *
     * <p>⚠️ 这里**只返回功能权限**。数据范围另行从 {@code users.data_scope} 取，
     * 两者是独立维度（《21-方案》§1.1）。
     */
    public Set<String> effectivePermissions(User user) {
        if (user == null) {
            return Set.of();
        }
        if (user.isAdmin()) {
            return new LinkedHashSet<>(mapper.selectAllPermCodes());
        }
        LinkedHashSet<String> perms = new LinkedHashSet<>(mapper.selectPermCodes(user.getId()));
        if (user.isEnterpriseAdmin()) {
            perms.addAll(EnterpriseAdminPermissions.CODES);
        }
        return perms;
    }

    /** 内置模板的 code 列表（供前端"角色列表"与运维核对）。 */
    public static List<String> builtinCodes() {
        return TEMPLATES.stream().map(Template::code).toList();
    }
}
