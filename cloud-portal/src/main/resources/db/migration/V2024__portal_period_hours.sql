-- =============================================================
-- V2024__portal_period_hours.sql  (计费周期单位：天 → 小时)
-- 依据《16 - 计费周期单位变更：天 → 小时》第十一节（2026-10-14）
--
-- 口径：产品侧统一按「小时」上报周期。1 天 = 24，30 天 = 720，3 小时 = 3。
--
--   ① 列改名 period_days → period_hours
--      （PostgreSQL 的 RENAME COLUMN 会自动同步 CHECK 约束里的引用：
--        ck_portal_sku_period / period_hours > 0 都会跟着改名，无需手工处理）
--   ② 存量值 ×24 —— 保证「30 天」仍然是「30 天」，不改变业务语义
--   ③ portal_provision 默认值 30 → 720（仍是 30 天，只是单位变成小时）
--
-- ⚠️ 明确不动的两处（见 11.1 第 3 条 / 11.2 注意点 2）：
--    · expire_at     ：已生效的到期时刻，改动会影响在期用户
--    · grace_end_at  ：宽限期本来就是「天」（PORTAL_SUBSCRIBE_GRACE_DAYS=7），与计费周期无关
--
-- ⚠️ 不要修改已执行过的 V2021 / V2022（Flyway 校验和会不匹配）。
--    种子商品的周期由本脚本的 `UPDATE ... * 24` 统一处理。
-- =============================================================

-- ① 商品 SKU 表：列改名 + 存量值 ×24
ALTER TABLE portal_product_sku RENAME COLUMN period_days TO period_hours;
UPDATE portal_product_sku SET period_hours = period_hours * 24 WHERE period_hours IS NOT NULL;
COMMENT ON COLUMN portal_product_sku.period_hours
    IS '周期长度（小时）；billing_mode=1 必填。1 天=24，30 天=720，3 小时=3';

-- ② 开通记录表：列改名 + 存量值 ×24 + 默认值改 720（原 30 天）
ALTER TABLE portal_provision RENAME COLUMN period_days TO period_hours;
UPDATE portal_provision SET period_hours = period_hours * 24;
ALTER TABLE portal_provision ALTER COLUMN period_hours SET DEFAULT 720;
COMMENT ON COLUMN portal_provision.period_hours
    IS '计费周期（小时）。1 天=24，30 天=720';
