-- 数据权限由 3 档扩展为 5 档（2026-10-29）
--
-- 5 档定义（chk_users_data_scope）：
--   self          仅个人                     本人
--   dept          本人及部门                 本人 + 本部门同事（不含子部门）
--   dept_below    本人和本人部门以下         本人 + 子部门（**不含**本部门同事）
--   dept_subtree  本人及本部门以下所有部门    本人 + 本部门 + 所有子部门
--   all           全公司                     本企业全员（仍受 enterprise_id 约束）
--
-- 注意：数据权限只决定"能看到谁的数据"；"能不能删/下载"由功能权限点决定
-- （file.delete 删自己 / file.other.delete 删可视范围内他人），两个维度正交。

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_data_scope;

ALTER TABLE users
  ADD CONSTRAINT chk_users_data_scope
  CHECK (data_scope IN ('self', 'dept', 'dept_below', 'dept_subtree', 'all'));

COMMENT ON COLUMN users.data_scope IS
  '数据权限：self 仅个人 / dept 本人及部门 / dept_below 本人和本人部门以下 / dept_subtree 本人及本部门以下所有部门 / all 全公司';