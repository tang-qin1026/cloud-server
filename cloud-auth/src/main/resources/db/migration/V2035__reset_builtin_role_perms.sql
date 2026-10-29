-- 内置角色权限重置为模板值（2026-10-29）
--
-- 起因：企业 5 的 dept_admin 被测试脚本改成了 14 项（含 billing.read / transfer.* /
-- file.other.delete），而企业 1、7 的同名角色是标准的 8 项。
-- 结果是"同一个内置角色在不同企业下权限不同"，排查时极易误判为代码 bug。
--
-- 内置角色是**模板**，不是用户自定义角色；管理员应通过"新建自定义角色"来定制，
-- 而不是改内置角色的权限点。因此重置为与 RbacService.TEMPLATES 完全一致的值。
--
-- ⚠️ 这会覆盖管理员对内置角色权限的手工调整（如有）。本环境属测试数据，按干净状态重置。

DELETE FROM role_permission rp
 USING role r
 WHERE rp.role_id = r.id
   AND r.builtin = TRUE
   AND r.code IN ('dept_admin', 'member');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, c.code
  FROM role r
  JOIN (VALUES
        ('dept_admin', 'file.read'),
        ('dept_admin', 'file.upload'),
        ('dept_admin', 'file.modify'),
        ('dept_admin', 'file.delete'),
        ('dept_admin', 'recycle.read'),
        ('dept_admin', 'recycle.restore'),
        ('dept_admin', 'recycle.purge'),
        ('dept_admin', 'quota.read'),
        ('member', 'file.read'),
        ('member', 'file.upload'),
        ('member', 'file.modify'),
        ('member', 'file.delete'),
        ('member', 'recycle.read'),
        ('member', 'recycle.restore'),
        ('member', 'recycle.purge'),
        ('member', 'transfer.read'),
        ('member', 'transfer.control'),
        ('member', 'quota.read'),
        ('member', 'billing.read'),
        ('member', 'increase.apply'),
        ('member', 'increase.read')
  ) AS c(role_code, code) ON r.code = c.role_code
 WHERE r.builtin = TRUE
ON CONFLICT DO NOTHING;