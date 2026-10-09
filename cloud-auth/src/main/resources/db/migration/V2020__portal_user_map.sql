-- =============================================================
-- V2020__portal_user_map.sql  (门户接入 · 影子用户映射)
-- 依据《云平台统一门户 API 契约 v1.0》§4.1
-- 库：PostgreSQL（由指南的 MySQL DDL 转写）：
--   CHAR(36)     -> VARCHAR(36)
--   DATETIME(3)  -> TIMESTAMPTZ（与库内其它表统一，避免时区错位）
--   ENGINE/COMMENT 内联 -> COMMENT ON ...
-- 号段说明：门户接入统一使用 V2020 起，避开主干 App 推送更新模块已占用的 V2011/V2012。
-- =============================================================

CREATE TABLE portal_user_map (
    portal_user_id VARCHAR(36)  NOT NULL,
    local_user_id  BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_portal_user_map       PRIMARY KEY (portal_user_id),
    -- 一个本地用户不能被两个门户用户占用（并发首达的兜底之一）
    CONSTRAINT uk_portal_user_map_local UNIQUE (local_user_id)
);

COMMENT ON TABLE  portal_user_map                 IS '门户用户 → 本地影子用户映射（首达建档，契约 §4.1）';
COMMENT ON COLUMN portal_user_map.portal_user_id  IS '门户用户 UUID（门户 JWT 的 sub）';
COMMENT ON COLUMN portal_user_map.local_user_id   IS '本系统 users.id';
COMMENT ON COLUMN portal_user_map.created_at      IS '建档时刻';
