-- 查阅移入数据权限，下载保留为功能权限点（2026-10-29）
--
-- 背景：文件列表/目录树的可见范围**一直**由数据权限决定
--   （FileNodeServiceImpl 用 resolveVisibleOwnerIds 过滤 owner_id），
--   file.read / file.other.read 这两个权限点从未被任何代码校验 ——
--   留在字典里只会让管理员以为"勾了才能看"，属误导。
--
-- 口径（合并此前两条要求）：
--   查阅（能看谁的文件） → 数据权限（self / dept / dept_below / dept_subtree / all）
--   下载 / 删除 / 上传 / 修改 → 功能权限点
--
-- 因此：
--   ① file.read「浏览与下载」→ 改名为 file.download「下载」（下载仍需管控）
--   ② file.other.read「查看下级的文件」→ 删除（纯查阅，已由数据范围覆盖）
--
-- ⚠️ 顺序很关键：role_permission.permission_code 有外键指向 permission.code，
--    必须先插入目标权限行，否则 UPDATE 引用时会违反 role_permission_permission_code_fkey
--    （第一版写成先 UPDATE，直接导致应用启动失败，已修正）。

-- ① 先插入新权限点 file.download（继承 file.read 的分组与排序）
INSERT INTO permission (code, name, group_code, sort_no, high_risk)
SELECT 'file.download', '下载', group_code, sort_no, high_risk
  FROM permission
 WHERE code = 'file.read'
ON CONFLICT (code) DO NOTHING;

-- ② 角色授权：file.read → file.download
UPDATE role_permission SET permission_code = 'file.download' WHERE permission_code = 'file.read';

-- ③ 删除纯查阅权限点 file.other.read（先删引用，再删字典行）
DELETE FROM role_permission WHERE permission_code = 'file.other.read';
DELETE FROM permission      WHERE code = 'file.other.read';

-- ④ 删除旧的 file.read 字典行
DELETE FROM permission WHERE code = 'file.read';