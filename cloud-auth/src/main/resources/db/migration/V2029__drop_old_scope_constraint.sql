-- 修正 V2028：旧约束是 PostgreSQL 自动命名的 users_data_scope_check，
-- V2028 只删了 chk_users_data_scope（当时并不存在），导致库里同时存在
-- 两个 CHECK，旧的严格约束仍在拦截 dept / dept_below。
--
-- 本迁移删掉自动命名的旧约束，只保留 V2028 建的 chk_users_data_scope（5 档）。

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_data_scope_check;

-- 幂等兜底：若环境里还残留同名自动约束，一并清掉
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'users'::regclass
          AND contype = 'c'
          AND conname <> 'chk_users_data_scope'
          AND pg_get_constraintdef(oid) LIKE '%data_scope%'
    LOOP
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', c.conname);
        RAISE NOTICE '已删除旧数据范围约束: %', c.conname;
    END LOOP;
END $$;