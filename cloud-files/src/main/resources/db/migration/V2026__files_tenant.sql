-- =============================================================
-- V2026__files_tenant.sql  (M4 文件租户化)
-- 依据：《24 - 企业版权限落地实施文档》v2 §5.2 + 《实现文档 v3》§6.1
--
-- 只加列 + 换索引，**不改任何既有列的语义**，可安全回滚（回滚代码即可，迁移不回滚）。
--
-- ⚠️ 索引必须**同时**加 enterprise_id 前缀，共 3 个：
--    漏一个 → 跨企业重名冲突（uk）或索引失效（idx）。
-- =============================================================

-- ① 加列（默认 0 = 个人域，与 users.enterprise_id 的口径一致）
ALTER TABLE files ADD COLUMN enterprise_id BIGINT NOT NULL DEFAULT 0;

-- ② 回填：按属主当前的企业归属回填存量文件
--    个人域用户（users.enterprise_id = 0）天然落 0，与默认值一致。
UPDATE files f
SET enterprise_id = COALESCE(u.enterprise_id, 0)
FROM users u
WHERE u.id = f.owner_id
  AND f.enterprise_id = 0
  AND COALESCE(u.enterprise_id, 0) <> 0;

-- ③ 同级重名唯一索引：必须按企业隔离，否则两家企业不能有同名文件
DROP INDEX IF EXISTS uk_files_sibling_name;
CREATE UNIQUE INDEX uk_files_sibling_name
    ON files (enterprise_id, owner_id, parent_id, name)
    WHERE deleted_at IS NULL;

-- ④ 列目录主索引
DROP INDEX IF EXISTS idx_files_owner_parent;
CREATE INDEX idx_files_owner_parent
    ON files (enterprise_id, owner_id, parent_id)
    WHERE deleted_at IS NULL;

-- ⑤ 回收站索引
DROP INDEX IF EXISTS idx_files_owner_deleted;
CREATE INDEX idx_files_owner_deleted
    ON files (enterprise_id, owner_id, deleted_at)
    WHERE deleted_at IS NOT NULL;

COMMENT ON COLUMN files.enterprise_id IS '所属企业；0 = 个人域。写入时由租户上下文决定，回填自 users.enterprise_id';
