-- =============================================================
-- V2022__portal_provision.sql  (门户接入 · 开通记录 + 幂等键)
-- 依据《云平台统一门户 API 契约 v1.0》§3.2 两条铁律：
--   1) order_item_id 必须建唯一索引 —— 门户超时/重试/进程重启都可能重复调，
--      必须保证同一个 order_item_id 只开通一次，重复调用直接返回首次结果。
--   2) 先落库再执行 —— 收到请求先写 provision 记录（order_item_id 唯一索引），再建实例；
--      否则并发重试会开出两个实例。
-- status 状态机：pending → granting → active → grace → expired（failed 为异常终态）
-- =============================================================

CREATE TABLE portal_provision (
    id              BIGSERIAL    PRIMARY KEY,
    order_item_id   BIGINT       NOT NULL,                       -- 门户订单项 id（幂等键）
    portal_user_id  VARCHAR(36)  NOT NULL,                       -- 门户用户 UUID
    local_user_id   BIGINT       NOT NULL,                       -- 本系统 users.id
    sku_id          VARCHAR(64)  NOT NULL,
    quantity        INTEGER      NOT NULL DEFAULT 1 CHECK (quantity >= 1),
    period_days     INTEGER      NOT NULL DEFAULT 30 CHECK (period_days > 0),
    granted_bytes   BIGINT       NOT NULL DEFAULT 0,             -- 实际授予的配额（字节）
    instance_id     VARCHAR(64),                                 -- 返回给门户的实例号
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','granting','active','grace','expired','failed')),
    expire_at       TIMESTAMPTZ,                                 -- 到期时刻
    grace_end_at    TIMESTAMPTZ,                                 -- 宽限期结束（到期 → 宽限 → 停机）
    quota_reclaimed BOOLEAN      NOT NULL DEFAULT FALSE,          -- 配额是否已回收（幂等标记）
    last_billed_at  TIMESTAMPTZ,                                 -- 按量计费(billing_mode=2)最近一次出账时刻
    extra           TEXT,                                        -- 门户透传的 extra 原文（JSON 字符串）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 铁律 1：幂等键唯一索引
    CONSTRAINT uk_portal_provision_order_item UNIQUE (order_item_id)
);

COMMENT ON TABLE  portal_provision                 IS '门户开通记录（order_item_id 幂等，先落库再建实例）';
COMMENT ON COLUMN portal_provision.order_item_id   IS '门户订单项 id，全局唯一，开通幂等键';
COMMENT ON COLUMN portal_provision.granted_bytes   IS '实际授予 users.quota_bytes 的字节数（quota_gb × quantity × 1024³）';
COMMENT ON COLUMN portal_provision.instance_id     IS '实例号，形如 st-000001（由主键推导，稳定可复现）';
COMMENT ON COLUMN portal_provision.status          IS 'pending/granting/active/grace/expired/failed';
COMMENT ON COLUMN portal_provision.grace_end_at    IS '宽限期结束时刻 = expire_at + grace_days（建议 7 天）';
COMMENT ON COLUMN portal_provision.quota_reclaimed IS '到期停机时是否已回收配额（条件更新幂等标记）';

-- 到期扫描（部分索引，只关心在期的）
CREATE INDEX idx_portal_provision_expire ON portal_provision(expire_at) WHERE status = 'active';
-- 宽限期扫描
CREATE INDEX idx_portal_provision_grace  ON portal_provision(grace_end_at) WHERE status = 'grace';
-- 用户维度的开通记录（运维/对账排查）
CREATE INDEX idx_portal_provision_user   ON portal_provision(local_user_id, created_at DESC);

