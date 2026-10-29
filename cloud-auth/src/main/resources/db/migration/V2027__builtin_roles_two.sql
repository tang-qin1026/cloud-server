-- 内置角色收敛为两个（2026-10-28 口径）
--
-- 背景：员工可被企业管理员分配的权限只有 15 项
--   （file.* / recycle.* / transfer.* / quota.read / billing.read / increase.apply / increase.read）
-- 原有内置角色中有超出该范围的内容：
--   auditor    = admin.audit.read
--   finance    = admin.billing.read, admin.increase.read, admin.increase.approve,
--                admin.ledger.read, admin.ledger.export
--   dept_admin 含 org.member.read, admin.dashboard.read
-- 这些都是管理员职责，不能作为员工角色存在，故：
--   1) 退役 auditor / finance（解绑成员 -> 清权限 -> 删角色）
--   2) 内置角色权限裁剪到 15 项以内
--   3) 内置角色权限重置为模板值（内置角色是模板而非用户自定义，
--      重置以保证"新建企业"与"已有企业"口径一致）

-- ① 退役 auditor / finance：先解绑成员，避免删角色后留下悬空引用
DO $$
DECLARE
    retired BIGINT[];
BEGIN
    SELECT array_agg(id) INTO retired
      FROM role WHERE builtin = TRUE AND code IN ('auditor', 'finance');

    IF retired IS NOT NULL THEN
        DELETE FROM user_role WHERE role_id = ANY(retired);
        DELETE FROM role_permission WHERE role_id = ANY(retired);
        DELETE FROM role WHERE id = ANY(retired);
        RAISE NOTICE '已退役内置角色 auditor/finance: %', retired;
    END IF;
END $$;

-- ② 裁剪：内置角色不得持有 15 项之外的权限点
DELETE FROM role_permission rp
USING role r
WHERE rp.role_id = r.id
  AND r.builtin = TRUE
  AND rp.permission_code NOT IN (
        'file.read', 'file.upload', 'file.modify', 'file.delete',
        'file.other.read', 'file.other.delete',
        'recycle.read', 'recycle.restore', 'recycle.purge',
        'transfer.read', 'transfer.control',
        'quota.read', 'billing.read',
        'increase.apply', 'increase.read'
      );

-- ③ 重置为模板值（与 RbacService.TEMPLATES 一致）
DELETE FROM role_permission rp
USING role r
WHERE rp.role_id = r.id AND r.builtin = TRUE AND r.code IN ('dept_admin', 'member');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, c.code
  FROM role r
  JOIN (VALUES
        ('dept_admin', 'file.read'),
        ('dept_admin', 'file.upload'),
        ('dept_admin', 'file.modify'),
        ('dept_admin', 'file.delete'),
        ('dept_admin', 'file.other.read'),
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