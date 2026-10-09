-- =============================================================
-- V2021__portal_product_sku.sql  (门户接入 · 商品目录)
-- 依据《云平台统一门户 API 契约 v1.0》§2 / §3.1
-- 本表是**商品的权威来源**：门户每 10 分钟拉取 GET /internal/products 并全量 upsert
-- 到它自己的 cloud_portal.p_product 缓存表（只返回 is_active=1，未上报的 sku 软下架）。
-- 因此本表的 sku_id 必须稳定：改 sku_id = 下架旧的 + 上架新的。
-- =============================================================

CREATE TABLE portal_product_sku (
    id            BIGSERIAL    PRIMARY KEY,
    sku_id        VARCHAR(64)  NOT NULL,                       -- 产品内唯一，<=64 字符
    name          VARCHAR(128) NOT NULL,                       -- 门户展示名，<=128 字符
    spec          JSONB        NOT NULL DEFAULT '{}'::jsonb,   -- 结构由本产品自定，门户原样展示
    price_cents   BIGINT       NOT NULL CHECK (price_cents >= 0), -- 单价，单位「分」（契约红线）
    billing_mode  SMALLINT     NOT NULL CHECK (billing_mode IN (1, 2)), -- 1 包周期 / 2 按量
    period_days   INTEGER,                                     -- billing_mode=1 必填
    stock         INTEGER      CHECK (stock IS NULL OR stock >= 0), -- NULL = 不限或未上报
    quota_gb      INTEGER      NOT NULL DEFAULT 0 CHECK (quota_gb >= 0), -- 开通时授予的配额（GB），产品侧私有字段
    sort_no       INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_portal_product_sku_sku_id UNIQUE (sku_id),
    -- 包周期必须给周期天数（契约 §3.1）
    CONSTRAINT ck_portal_sku_period CHECK (billing_mode <> 1 OR period_days IS NOT NULL)
);

COMMENT ON TABLE  portal_product_sku              IS '门户商品目录（权威来源），门户每 10 分钟全量拉取';
COMMENT ON COLUMN portal_product_sku.sku_id       IS '产品内唯一的 SKU 标识，变更即视为下架旧 SKU';
COMMENT ON COLUMN portal_product_sku.spec         IS '规格，任意结构，门户原样展示（前端按 key 通用渲染）';
COMMENT ON COLUMN portal_product_sku.price_cents  IS '单价（分）。全链路以分为单位，仅在展示时除 100';
COMMENT ON COLUMN portal_product_sku.billing_mode IS '1=包周期（下单即扣）；2=按量（购买时 0 元，产品侧后付费出账）';
COMMENT ON COLUMN portal_product_sku.stock        IS '库存；NULL=不限（前端显示"现货"）。云存储不限库存';
COMMENT ON COLUMN portal_product_sku.quota_gb     IS '开通该 SKU 时授予用户的存储配额（GB），产品侧私有';

-- 种子商品：按现有计费口径（100 分/GB/月 = 1 元/GB/月，包周期 30 天）折算
INSERT INTO portal_product_sku (sku_id, name, spec, price_cents, billing_mode, period_days, stock, quota_gb, sort_no)
VALUES
    ('storage-100g', '云存储·100G',
     '{"quota_gb":100,"transfer":"unlimited","period":"30天"}'::jsonb,  10000, 1, 30, NULL,  100, 10),
    ('storage-500g', '云存储·500G',
     '{"quota_gb":500,"transfer":"unlimited","period":"30天"}'::jsonb,  50000, 1, 30, NULL,  500, 20),
    ('storage-1t',   '云存储·1T',
     '{"quota_gb":1024,"transfer":"unlimited","period":"30天"}'::jsonb, 102400, 1, 30, NULL, 1024, 30),
    ('storage-2t',   '云存储·2T',
     '{"quota_gb":2048,"transfer":"unlimited","period":"30天"}'::jsonb, 204800, 1, 30, NULL, 2048, 40);
