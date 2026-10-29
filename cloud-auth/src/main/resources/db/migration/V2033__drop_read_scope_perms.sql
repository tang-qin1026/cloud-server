-- 移除文件权限点里的"查看/浏览"语义，避免与数据权限交叉（2026-10-29）
--
-- 原则：能看谁的文件完全由数据权限 5 档决定
--   SELF / DEPT / DEPT_BELOW / DEPT_AND_BELOW / ALL
-- 权限字典里**不应再有**"浏览 / 查看下级"这类范围型权限点，
-- 否则同一个"能不能看"会被两个地方决定，管理员配了也不生效（此前从未被代码校验）。
--
--   file.read        浏览与下载 → 名称改为「下载」   （code 不变；保留是因为下载仍需管控）
--   file.other.read  查看下级的文件 → **删除**         （纯范围语义，完全由数据权限替代）
--
-- code 不变是刻意的：前端按 code 判断按钮，改 code 会造成大面积返工。

-- ① file.read 只保留"下载"语义（code 不变，只改名）
UPDATE permission SET name = '下载' WHERE code = 'file.read';

-- ② 删除 file.other.read（先删授权引用，再删字典行；外键 role_permission.permission_code）
DELETE FROM role_permission WHERE permission_code = 'file.other.read';
DELETE FROM permission      WHERE code = 'file.other.read';