-- 权限点显示名称调整（2026-10-29）
--
-- code 不变，只改 name，让名称直接说明"能删谁的"，避免管理员误解：
--   file.delete        删除           → 仅删除个人文件
--   file.other.delete  删除下级的文件 → 仅删除可见范围内他人的文件
--
-- 原因：原名称"删除下级的文件"暗示"只对下级生效"，但实际语义是
-- "可见范围内他人的文件"（范围由数据权限决定，可能是同部门/子部门/全公司）。

UPDATE permission SET name = '仅删除个人文件'
 WHERE code = 'file.delete';

UPDATE permission SET name = '仅删除可见范围内他人的文件'
 WHERE code = 'file.other.delete';