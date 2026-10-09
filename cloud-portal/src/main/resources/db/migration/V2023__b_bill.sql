-- =============================================================
-- V2023__b_bill.sql  (门户接入 · 账单表，先落库再扣费)
-- 依据《云平台统一门户 API 契约 v1.0》§4.2 / §4.3 / §4.4
-- 铁律：**先生成 bill_no 落库（status=0 待扣），再调门户 POST /wallet/deduct**
--   若先调门户再落库，写完库前进程挂了 → 本侧没有账单记录、对面钱已扣，
--   对账时这笔钱永远说不清。先落库，最坏情况是「有一笔待扣账单没扣成」，可修。
-- =============================================================

CREATE TABLE b_bill (
    bill_no         VARCHAR(64) PRIMARY KEY,                     -- 本侧账单号，全局唯一
    local_user_id   BIGINT      NOT NULL,                        -- 本系统 users.id
    portal_user_id  VARCHAR(36) NOT NULL,                        -- 门户用户 UUID（对账上报用；契约 §6 要求上报）
    product_code    VARCHAR(32) NOT NULL,                        -- storage / phone / desktop
    amount_cents    BIGINT      NOT NULL CHECK (amount_cents >= 0), -- 单位「分」
    type            SMALLINT    NOT NULL CHECK (type IN (1, 2, 3)), -- 1 续费 / 2 按量 / 3 退款
    related_bill_no VARCHAR(64),                                 -- 退款单指向的原单
    status          SMALLINT    NOT NULL DEFAULT 0 CHECK (status IN (0, 1, 2)),
    fail_code       INTEGER,                                     -- 最近一次失败的响应码（3001/3002/5001…）
    retry_count     INTEGER     NOT NULL DEFAULT 0,              -- 系统失败重试次数
    biz_date        DATE        NOT NULL DEFAULT CURRENT_DATE,   -- 账期（对账按此日聚合）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  b_bill                 IS '门户账单（先落库 status=0，再调门户扣费）';
COMMENT ON COLUMN b_bill.bill_no         IS '本侧账单号，形态 product_code-yyyyMMdd-000123，门户扣费幂等键';
COMMENT ON COLUMN b_bill.portal_user_id  IS '门户用户 UUID；对账上报 payload 必填，故本表必须留存（契约 §6）';
COMMENT ON COLUMN b_bill.amount_cents    IS '金额（分）';
COMMENT ON COLUMN b_bill.type            IS '1 续费 / 2 按量 / 3 退款';
COMMENT ON COLUMN b_bill.status          IS '0 待扣 / 1 已入账 / 2 已退款（退款单本身成功后记 1）';
COMMENT ON COLUMN b_bill.biz_date        IS '账期日；每日对账上报按该字段取数';

CREATE INDEX idx_b_bill_local_user ON b_bill(local_user_id, created_at DESC);
CREATE INDEX idx_b_bill_biz_date   ON b_bill(biz_date, product_code);
-- 退款累计校验 / 原单反查
CREATE INDEX idx_b_bill_related    ON b_bill(related_bill_no) WHERE related_bill_no IS NOT NULL;

-- 账单号序列：生成 storage-20261008-000123 形态的 bill_no（并发安全，不依赖应用侧自增）
CREATE SEQUENCE portal_bill_seq START 1;
