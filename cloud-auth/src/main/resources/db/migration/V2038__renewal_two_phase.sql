-- 续费两阶段记账（2026-11-03 第二次，修正回调识别）
--
-- 背景：续费 = 门户扣款（不可回退）+ 本地延长。两者不可能在一个事务里。
-- 之前只有"扣款后写入"：一旦扣款成功但写库失败，subscription_renewal 里没有记录，
-- 门户随后的 /internal/provision 回调就认不出这是续费单，按【新购买】发放容量 ——
-- 结果：付 1 次钱，拿到「容量多一份」且「到期 +30 天」两份收益。
--
-- 两阶段做法：
--   ① 扣款前先落 pending（独立提交，不随随后的事务回滚消失）
--   ② 扣款成功后转 applied 并延长到期
--   ③ 回调进来时，无论记录处于 pending 还是 applied，都能认出"这是续费单"→ 跳过发放
--
-- 关键修正（第一次实现漏掉的）：回调识别条件必须包含 pending。
--   第一次只匹配 status='applied'，所以"记了 pending 但还没延长"时，回调仍被当成新购买。
--
-- callback_consumed 单独一列，表示"门户回调已认领"，与 status 正交：
--   回调可能早于延长到达（pending 状态就被认领），不能用 status 兼表这件事。
ALTER TABLE subscription_renewal DROP CONSTRAINT IF EXISTS ck_renewal_status;
ALTER TABLE subscription_renewal
    ADD CONSTRAINT ck_renewal_status
        CHECK (status IN ('pending', 'applied', 'consumed', 'orphan'));

ALTER TABLE subscription_renewal
    ADD COLUMN IF NOT EXISTS callback_consumed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE subscription_renewal SET callback_consumed = TRUE WHERE status = 'consumed';

CREATE INDEX IF NOT EXISTS idx_renewal_match_pending
    ON subscription_renewal (enterprise_id, sku_id, quantity, status, callback_consumed, created_at);

COMMENT ON COLUMN subscription_renewal.status IS
    'pending=已记账待扣款; applied=已扣款已延长; consumed=历史状态(等价 applied+已认领); orphan=超时需人工核对';
COMMENT ON COLUMN subscription_renewal.callback_consumed IS
    '门户回调是否已认领该续费单（认领后回调不再发放容量）；与 status 正交，可能先于延长发生';