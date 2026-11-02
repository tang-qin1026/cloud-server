-- 套餐续费记录（2026-11-02）
--
-- 语义：**续费 ≠ 新买**。
--   新买   → 新增一笔 enterprise_pool_grant（容量累加）
--   续费   → 只把**原有那笔** grant/provision 的到期时间往后推一个周期，
--            容量不变（份额不重复叠加）。
--
-- 为什么要单独一张表：
--   续费本身要付钱（走门户下单），而门户随后还会回调 /internal/provision 送一笔新 order_item。
--   我们必须能识别"这次回调其实是续费单"，从而**不新增 grant**。
--   本表就是那个识别依据：下单成功先落一行 status='applied'，
--   回调按 (enterprise_id, sku_id, quantity) + 时间窗匹配到它 → 置 'consumed' 并跳过发放。
CREATE TABLE IF NOT EXISTS subscription_renewal (
    id                   BIGSERIAL PRIMARY KEY,
    enterprise_id        BIGINT      NOT NULL,
    grant_order_item_id  BIGINT      NOT NULL,       -- 被续费的那笔开通
    sku_id               VARCHAR(64) NOT NULL,
    quantity             INTEGER     NOT NULL DEFAULT 1,
    extend_hours         INTEGER     NOT NULL,
    expire_at_before     TIMESTAMPTZ NOT NULL,
    expire_at_after      TIMESTAMPTZ NOT NULL,
    client_order_key     VARCHAR(64) NOT NULL,
    portal_order_id      VARCHAR(64),
    price_cents          BIGINT      NOT NULL DEFAULT 0,
    -- applied  = 已付款并已延长，等门户回调来认领
    -- consumed = 门户回调已到并被识别为续费（未新增额度）
    -- orphan   = 超时未等到回调（仅记录，便于对账）
    status               VARCHAR(16) NOT NULL DEFAULT 'applied',
    matched_order_item_id BIGINT,                    -- 门户回调里对应的新 order_item
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_renewal_key UNIQUE (client_order_key),
    CONSTRAINT ck_renewal_status CHECK (status IN ('applied','consumed','orphan')),
    CONSTRAINT ck_renewal_hours CHECK (extend_hours > 0)
);

CREATE INDEX IF NOT EXISTS idx_renewal_match
    ON subscription_renewal (enterprise_id, sku_id, quantity, status);

CREATE INDEX IF NOT EXISTS idx_renewal_grant
    ON subscription_renewal (grant_order_item_id);

COMMENT ON TABLE subscription_renewal IS '套餐续费记录：续费只延长到期时间，不新增容量';