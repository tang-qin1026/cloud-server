-- 企业池资源分配（docs/31）+ 免费额度改为 1GB（2026-10-30）
--
-- 一、免费额度 20GB → 1GB
--    只改"仍是旧默认值且没有购买增量"的用户，避免动到已购买/已手工调整过的账号。

ALTER TABLE users ALTER COLUMN quota_bytes SET DEFAULT 1073741824;

UPDATE users
   SET quota_bytes = 1073741824,
       updated_at  = now()
 WHERE quota_bytes = 21474836480
   AND COALESCE(extra_bytes, 0) = 0;

-- 二、企业池：按笔记录（一次购买一行，各自到期）
--    与个人侧 billing_records / extra_deducted 同构，减少两套心智模型。

CREATE TABLE IF NOT EXISTS enterprise_pool_grant (
    id                  BIGSERIAL PRIMARY KEY,
    enterprise_id       BIGINT       NOT NULL,
    order_item_id       BIGINT       NOT NULL,
    granted_bytes       BIGINT       NOT NULL,
    expire_at           TIMESTAMPTZ  NOT NULL,
    grace_end_at        TIMESTAMPTZ  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'active',
    reclaimed_deducted  BOOLEAN      NOT NULL DEFAULT FALSE,
    reclaimed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_pool_grant_order_item UNIQUE (order_item_id),
    CONSTRAINT ck_pool_grant_status CHECK (status IN ('active', 'grace', 'reclaimed'))
);

CREATE INDEX IF NOT EXISTS idx_pool_grant_ent_status ON enterprise_pool_grant (enterprise_id, status);
CREATE INDEX IF NOT EXISTS idx_pool_grant_expire ON enterprise_pool_grant (expire_at) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_pool_grant_grace ON enterprise_pool_grant (grace_end_at) WHERE status = 'grace';

-- 三、企业池汇总：上传热路径只读这一行，不扫明细

CREATE TABLE IF NOT EXISTS enterprise_pool (
    enterprise_id    BIGINT PRIMARY KEY,
    total_bytes      BIGINT      NOT NULL DEFAULT 0,
    allocated_bytes  BIGINT      NOT NULL DEFAULT 0,
    used_bytes       BIGINT      NOT NULL DEFAULT 0,
    over_allocated   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pool_non_negative CHECK (total_bytes >= 0 AND allocated_bytes >= 0 AND used_bytes >= 0)
);

-- 四、加列

-- 分给该成员的额度（闸 1 的第三段）
ALTER TABLE users ADD COLUMN IF NOT EXISTS enterprise_allocated_bytes BIGINT NOT NULL DEFAULT 0;

-- 这笔开通进企业池还是进个人（空 = 个人，保持现有行为不变）
ALTER TABLE portal_provision ADD COLUMN IF NOT EXISTS enterprise_id BIGINT NULL;

-- 五、为已存在的企业建池汇总行（幂等）
INSERT INTO enterprise_pool (enterprise_id)
SELECT id FROM enterprise WHERE id > 0
ON CONFLICT (enterprise_id) DO NOTHING;