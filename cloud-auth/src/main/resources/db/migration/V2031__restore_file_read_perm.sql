-- 还原 file.read / file.other.read（2026-10-29 决定：不改名、保持原有命名）
--
-- V2030 曾把 file.read 改名为 file.download 并删除 file.other.read。
-- 现按原始口径还原：
--   file.read        浏览与下载      file 组 sort_no=10
--   file.other.read  查看下级的文件  file 组 sort_no=17
--
-- 查阅（能看谁的文件）在行为上**仍由数据权限控制**（列表/树按 owner 范围过滤），
-- 这两个权限点保持"存在但不做强校验"的既有状态，不改名、不删除。
--
-- ⚠️ 顺序：role_permission.permission_code → permission.code 有外键，
--    必须先插入目标字典行再改引用。

-- ① 恢复 file.read 字典行（从 file.download 继承分组与排序，再纠正为标准值）
INSERT INTO permission (code, name, group_code, sort_no, high_risk)
VALUES ('file.read', '浏览与下载', 'file', 10, FALSE)
ON CONFLICT (code) DO NOTHING;

-- ② 引用改回 file.read
UPDATE role_permission SET permission_code = 'file.read' WHERE permission_code = 'file.download';

-- ③ 删除 file.download 字典行
DELETE FROM permission WHERE code = 'file.download';

-- ④ 恢复 file.other.read 字典行（原始 sort_no=17）
INSERT INTO permission (code, name, group_code, sort_no, high_risk)
VALUES ('file.other.read', '查看下级的文件', 'file', 17, FALSE)
ON CONFLICT (code) DO NOTHING;

-- ⑤ 恢复内置角色 dept_admin 的 file.other.read 授权
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'file.other.read'
  FROM role r
 WHERE r.builtin = TRUE AND r.code = 'dept_admin'
ON CONFLICT DO NOTHING;