package com.company.cloud.auth.enterprise;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业池额度：入池、分配、到期回收（docs/31）。
 *
 * <p><b>为什么放在 cloud-auth 而不是 cloud-billing</b>：
 * 入池发生在 <b>cloud-portal</b> 的 provision 回调里，而 cloud-portal 只依赖
 * cloud-common + cloud-auth（不依赖 cloud-billing）。把池逻辑放在 cloud-auth，
 * 让 provision（cloud-portal）、管理接口与到期任务（cloud-billing）共用同一份实现，
 * 避免在两个模块里各写一套 SQL 然后慢慢漂移。
 *
 * <h3>为什么按笔记账</h3>
 * 包月套餐月中加购，两笔到期时间不同。单 {@code expire_at} 列必然出错
 * （到期一笔就把整池都判过期）。所以 {@code enterprise_pool_grant} 一次购买一行，
 * 各自推进 {@code active → grace → reclaimed}；{@code enterprise_pool} 只做汇总缓存。
 *
 * <h3>三档状态</h3>
 * <table>
 *   <tr><th>状态</th><th>触发</th><th>池</th><th>成员</th></tr>
 *   <tr><td>active</td><td>购买后</td><td>计入 total</td><td>正常上传</td></tr>
 *   <tr><td>grace</td><td>expire_at ≤ now</td><td>不变</td><td>可读可下载，禁上传</td></tr>
 *   <tr><td>reclaimed</td><td>grace_end_at ≤ now</td><td>total 扣减本笔</td><td>额度回落个人</td></tr>
 * </table>
 *
 * <p>幂等：{@code reclaimed_deducted} 标记，任务重跑/并发都不会重复扣减
 * （与个人侧 {@code extra_deducted} 同一套做法）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterprisePoolService {

    private final JdbcTemplate jdbc;

    // ── 池概览 ────────────────────────────────────────────────

    /** 池汇总；没有行时返回全 0 的空池（不抛异常，前端好处理）。 */
    public Map<String, Object> pool(long enterpriseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT total_bytes, allocated_bytes, used_bytes, over_allocated, updated_at "
                        + "FROM enterprise_pool WHERE enterprise_id = ?", enterpriseId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("enterpriseId", enterpriseId);
            out.put("totalBytes", 0L);
            out.put("allocatedBytes", 0L);
            out.put("usedBytes", 0L);
            out.put("availableBytes", 0L);
            out.put("availableBytesRaw", 0L);
            out.put("overAllocated", false);
            out.put("suspended", false);
            out.put("nextExpireAt", null);
            return out;
        }
        Map<String, Object> r = rows.get(0);
        long total = num(r.get("total_bytes"));
        long allocated = num(r.get("allocated_bytes"));
        long used = num(r.get("used_bytes"));
        out.put("enterpriseId", enterpriseId);
        out.put("totalBytes", total);
        out.put("allocatedBytes", allocated);
        out.put("usedBytes", used);
        // 还能再分多少。为负 = 超额（池缩水但分配没收回来）。
        out.put("availableBytes", Math.max(0L, total - allocated));
        // 原始值（可能为负）：前端据此置灰「上传/下载/预览」，并显示差额
        out.put("availableBytesRaw", total - allocated);
        out.put("overAllocated", Boolean.TRUE.equals(r.get("over_allocated")));
        // 超额即停用用户端的这三个动作（平台级限制，凌驾于角色权限之上）
        out.put("suspended", (total - allocated) < 0);
        out.put("nextExpireAt", nextExpireAt(enterpriseId));
        return out;
    }

    private Object nextExpireAt(long enterpriseId) {
        // 直接按 OffsetDateTime 取：timestamptz → java.sql.Timestamp 没有 toOffsetDateTime()
        List<OffsetDateTime> ts = jdbc.queryForList(
                "SELECT MIN(expire_at) AS t FROM enterprise_pool_grant "
                        + "WHERE enterprise_id = ? AND status = 'active'", OffsetDateTime.class, enterpriseId);
        return ts.isEmpty() ? null : ts.get(0);
    }

    // ── 分配 ──────────────────────────────────────────────────

    /**
     * 设置某成员的企业额度（0 = 收回）。
     *
     * <p>校验：{@code 已分配 + 本次增量 ≤ 池总量}，且池未处于超卖。
     * 超卖时**只收不放**（允许下调，禁止上调）—— 削分配会直接影响用户文件，不能自动做。
     */
    public void allocate(long enterpriseId, long localUserId, long newBytes) {
        if (newBytes < 0) {
            throw new IllegalArgumentException("额度不能为负");
        }
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT COALESCE(enterprise_allocated_bytes, 0) AS a FROM users "
                        + "WHERE id = ? AND enterprise_id = ?", localUserId, enterpriseId);
        if (cur.isEmpty()) {
            throw new IllegalArgumentException("该用户不属于本企业");
        }
        long oldBytes = num(cur.get(0).get("a"));
        long delta = newBytes - oldBytes;

        if (delta != 0) {
            Map<String, Object> p = pool(enterpriseId);
            long total = (Long) p.get("totalBytes");
            long allocated = (Long) p.get("allocatedBytes");
            boolean over = (Boolean) p.get("overAllocated");
            if (delta > 0) {
                if (over) {
                    throw new IllegalArgumentException(
                            "企业池已超卖（已分配 " + gb(allocated) + "，池 " + gb(total)
                                    + "），只能下调分配，请先续费");
                }
                if (allocated + delta > total) {
                    throw new IllegalArgumentException(
                            "超出企业池剩余：可分配 " + gb(Math.max(0, total - allocated)) + "，本次需要 " + gb(delta));
                }
            }
            jdbc.update("UPDATE enterprise_pool SET allocated_bytes = GREATEST(0, allocated_bytes + ?), "
                    + "over_allocated = (total_bytes < GREATEST(0, allocated_bytes + ?)), updated_at = now() "
                    + "WHERE enterprise_id = ?", delta, delta, enterpriseId);
        }
        jdbc.update("UPDATE users SET enterprise_allocated_bytes = ?, updated_at = now() WHERE id = ?",
                newBytes, localUserId);
    }

    /** 某企业的成员分配清单（成员名、分配、已用）。 */
    public List<Map<String, Object>> allocations(long enterpriseId) {
        return jdbc.queryForList(
                // 别名统一 camelCase：与 pool / quota 两个接口保持一致，
                // 前端不用为不同接口记两套命名风格。
                "SELECT id AS \"localUserId\", "
                        + "       COALESCE(nickname, username) AS \"displayName\", "
                        + "       COALESCE(enterprise_allocated_bytes, 0) AS \"allocatedBytes\", "
                        + "       COALESCE(used_bytes, 0) AS \"usedBytes\", "
                        + "       enterprise_role AS \"enterpriseRole\" "
                        + "FROM users WHERE enterprise_id = ? ORDER BY id", enterpriseId);
    }

    // ── 入池与到期 ────────────────────────────────────────────

    /**
     * 一笔购买入池（幂等：{@code order_item_id} 唯一）。
     *
     * @return true = 新生效；false = 该订单项已入池过（重复回调，不重复加）
     */
    public boolean grant(long enterpriseId, long orderItemId, long grantedBytes,
                         OffsetDateTime expireAt, int graceDays) {
        int inserted = jdbc.update(
                "INSERT INTO enterprise_pool_grant "
                        + "(enterprise_id, order_item_id, granted_bytes, expire_at, grace_end_at, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'active') ON CONFLICT (order_item_id) DO NOTHING",
                enterpriseId, orderItemId, grantedBytes,
                Timestamp.from(expireAt.toInstant()),
                Timestamp.from(expireAt.plusDays(graceDays).toInstant()));
        if (inserted == 0) {
            log.info("[pool] 订单项已入池，跳过 enterpriseId={} orderItemId={}", enterpriseId, orderItemId);
            return false;
        }
        recompute(enterpriseId);
        log.info("[pool] 入池 enterpriseId={} orderItemId={} bytes={} expireAt={}",
                enterpriseId, orderItemId, grantedBytes, expireAt);
        return true;
    }

    /**
     * 重算池汇总（total = 未回收之和；allocated 由用户列汇总，防漂移）。
     *
     * <p>上传热路径只读 {@code enterprise_pool}，所以这里允许稍微多算几次 —— 一致性优先。
     */
    public void recompute(long enterpriseId) {
        jdbc.update(
                "INSERT INTO enterprise_pool (enterprise_id, total_bytes, allocated_bytes, used_bytes, over_allocated) "
                        + "SELECT ?, "
                        + "  COALESCE((SELECT SUM(granted_bytes) FROM enterprise_pool_grant "
                        + "            WHERE enterprise_id = ? AND status IN ('active','grace')), 0), "
                        + "  COALESCE((SELECT SUM(enterprise_allocated_bytes) FROM users WHERE enterprise_id = ?), 0), "
                        + "  COALESCE((SELECT SUM(used_bytes) FROM users WHERE enterprise_id = ?), 0), "
                        + "  false "
                        + "ON CONFLICT (enterprise_id) DO UPDATE SET "
                        + "  total_bytes = EXCLUDED.total_bytes, "
                        + "  allocated_bytes = EXCLUDED.allocated_bytes, "
                        + "  used_bytes = EXCLUDED.used_bytes, "
                        + "  updated_at = now()",
                enterpriseId, enterpriseId, enterpriseId, enterpriseId);
        // 超卖标记：池总量 < 已分配
        jdbc.update("UPDATE enterprise_pool SET over_allocated = (total_bytes < allocated_bytes), updated_at = now() "
                + "WHERE enterprise_id = ?", enterpriseId);
    }

    /**
     * 到期回收：{@code active → reclaimed}，**没有宽限期**，到点直接扣减池总量。
     *
     * <h3>为什么取消宽限期（2026-10-30 口径变更）</h3>
     * 原设计是「到期 → 7 天宽限（可读可下载、禁上传）→ 回收」。
     * 现改为**到期即回收**：企业空间是预付制，过期就该停；
     * 宽限期会让"欠费还能读"变成长期状态，也和「可分配额度为负即停用」这条新规则冲突。
     *
     * <p>回收后若 {@code allocated > total}（可分配额度为负数 = 超卖），
     * 该企业**所有账号不能登录用户端**（企业管理员仍可进管理端把分配降下来）——
     * 这个判定在登录侧做，见 {@code AuthService}。
     *
     * <p>幂等：{@code reclaimed_deducted} 标记，任务重跑/并发都不会重复扣。
     *
     * @return 本轮回收条数
     */
    public int advanceStates(OffsetDateTime now) {
        // 兼容历史数据：仍在 grace 且已过期的，一并按"到期"处理
        int reclaimed = jdbc.update(
                "UPDATE enterprise_pool_grant SET status = 'reclaimed', "
                        + "  reclaimed_deducted = TRUE, reclaimed_at = ? "
                        + "WHERE status IN ('active', 'grace') AND expire_at <= ? "
                        + "  AND reclaimed_deducted = FALSE",
                Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
        if (reclaimed > 0) {
            log.info("[pool] 到期直接回收 now={} 回收={} 笔（无宽限期）", now, reclaimed);
            List<Long> ents = jdbc.queryForList(
                    "SELECT DISTINCT enterprise_id FROM enterprise_pool_grant "
                            + "WHERE status = 'reclaimed'", Long.class);
            for (Long e : ents) {
                recompute(e);
            }
        }
        return reclaimed;
    }

    // ── 套餐清单 / 续费 ───────────────────────────────────────

    /**
     * 每一笔套餐（含已回收的，标出状态）。
     *
     * <p>JOIN 商品目录拿 sku 名称与单价 —— 管理员看清单时要能对上"这是哪个套餐、多少钱"。
     * 商品信息经 {@code portal_provision} 反查（grant 表只存 order_item_id，不冗余 sku）。
     */
    public List<Map<String, Object>> subscriptions(long enterpriseId) {
        // 字段说明（2026-11-02 修正：原来只给 quota_gb + granted_bytes，
        // quantity>1 时前端会算成"份额对不上"）：
        //   quantity     数量（下单买了几份）
        //   unitQuotaGb  单份容量
        //   totalQuotaGb quantity × 单份容量  ← 与 grantedBytes 对应
        //   priceCents   单份价格（分）
        //   totalPriceCents quantity × 单份价格  ← 这一笔总共花了多少
        //   renewals     该笔被续费过几次（续费只延长时间，不加容量，见 subscription_renewal）
        return jdbc.queryForList(
                "SELECT g.order_item_id AS \"orderItemId\", "
                        + "       g.enterprise_id AS \"enterpriseId\", "
                        + "       COALESCE(pp.sku_id, '') AS \"skuId\", "
                        + "       p.name AS \"skuName\", "
                        + "       COALESCE(pp.quantity, 1) AS \"quantity\", "
                        + "       p.quota_gb AS \"unitQuotaGb\", "
                        + "       (COALESCE(p.quota_gb, 0) * COALESCE(pp.quantity, 1)) AS \"totalQuotaGb\", "
                        + "       g.granted_bytes AS \"grantedBytes\", "
                        + "       p.price_cents AS \"priceCents\", "
                        + "       (COALESCE(p.price_cents, 0) * COALESCE(pp.quantity, 1)) AS \"totalPriceCents\", "
                        + "       COALESCE(pp.period_hours, 720) AS \"periodHours\", "
                        + "       COALESCE(pp.period_hours, 720) / 24 AS \"periodDays\", "
                        + "       pp.created_at AS \"createdAt\", "
                        + "       g.status AS \"status\", "
                        + "       g.expire_at AS \"expireAt\", "
                        + "       g.reclaimed_at AS \"reclaimedAt\", "
                        + "       GREATEST(0, EXTRACT(DAY FROM (g.expire_at - now())))::int AS \"daysRemaining\", "
                        + "       (g.status = 'reclaimed' OR g.expire_at <= now()) AS \"expired\", "
                        + "       COALESCE((SELECT count(*) FROM subscription_renewal r "
                        + "                  WHERE r.grant_order_item_id = g.order_item_id "
                        + "                    AND r.status IN ('applied','consumed')), 0) AS \"renewals\" "
                        + "FROM enterprise_pool_grant g "
                        + "LEFT JOIN portal_provision pp ON pp.order_item_id = g.order_item_id "
                        + "LEFT JOIN portal_product_sku p ON p.sku_id = pp.sku_id "
                        + "WHERE g.enterprise_id = ? "
                        + "ORDER BY g.expire_at DESC, g.id DESC", enterpriseId);
    }

    /**
     * 续费某笔开通：**只把到期时间往后推一个周期，容量不变**。
     *
     * <p>与"新买"的区别（2026-11-02 口径）：
     * <ul>
     *   <li>新买 → 新增一笔 {@code enterprise_pool_grant}，容量累加</li>
     *   <li><b>续费 → 延长原有那笔的 expire_at，不新增 grant、不重复加容量</b></li>
     * </ul>
     *
     * <h3>为什么要落 subscription_renewal</h3>
     * 续费同样要付钱：调用方先在门户下单，门户扣款后会回调 {@code /internal/provision}
     * 送一笔**新的 order_item**。我们靠本表识别"这次回调其实是续费单"，
     * 从而跳过发放（否则容量会被重复加一次）。
     *
     * @return 延长前后的到期时间，供接口回给前端
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRenewal(long enterpriseId, long orderItemId,
                                            String clientOrderKey, String portalOrderId,
                                            long priceCents) {
        // 事务边界（2026-11-03 补）：下面有 3 条写 + 一次池重算，必须原子。
        // 没有事务时的真实风险：门户已经扣了钱，若这里第 1 条 UPDATE 成功、
        // 第 2 条失败，就会出现 grant 已延长、provision 还是旧到期时间，
        // 04:00 的 provision 定时任务会按旧时间把它判成过期并回收 ——
        // 表现为"续费成功了，但又被打回到期"。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.order_item_id, g.expire_at, g.status, g.granted_bytes, "
                        + "       COALESCE(pp.sku_id, '') AS sku_id, "
                        + "       COALESCE(pp.quantity, 1) AS quantity, "
                        + "       COALESCE(pp.period_hours, 720) AS period_hours, "
                        + "       pp.id AS provision_id "
                        + "FROM enterprise_pool_grant g "
                        + "LEFT JOIN portal_provision pp ON pp.order_item_id = g.order_item_id "
                        + "WHERE g.enterprise_id = ? AND g.order_item_id = ?",
                enterpriseId, orderItemId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到该套餐：" + orderItemId);
        }
        Map<String, Object> r = rows.get(0);

        // ── 幂等（重要）：这个 clientOrderKey 已经**延长过**了就直接返回旧结果 ──
        //
        // 门户对重复 clientOrderKey 返回 3003（duplicated=true，**本次没扣款**）。
        // 若此处再延长一次，就会出现"只扣一次钱、到期时间被推 N 次"——
        // 真实踩过：同一笔 355 连点 4 次，到期从 2026-12-02 被推到 2027-04-01，
        // 但钱包只扣了 1 期（100 元）。
        //
        // 注意：pending / orphan 不算"已完成" —— 那是两阶段记账的中间态
        // （已记账但还没扣款/还没延长），必须继续往下走完成延长。
        List<Map<String, Object>> done = jdbc.queryForList(
                "SELECT id, status, expire_at_before, expire_at_after, extend_hours "
                        + "FROM subscription_renewal WHERE client_order_key = ?", clientOrderKey);
        if (!done.isEmpty() && !"pending".equals(String.valueOf(done.get(0).get("status")))
                && !"orphan".equals(String.valueOf(done.get(0).get("status")))) {
            Map<String, Object> d = done.get(0);
            log.info("[renew] 幂等命中 clientOrderKey={} 该笔已延长到 {}，本次不再延长",
                    clientOrderKey, d.get("expire_at_after"));
            Map<String, Object> again = new LinkedHashMap<>();
            again.put("orderItemId", orderItemId);
            again.put("skuId", r.get("sku_id"));
            again.put("quantity", ((Number) r.get("quantity")).intValue());
            again.put("extendHours", ((Number) d.get("extend_hours")).intValue());
            again.put("extendDays", ((Number) d.get("extend_hours")).intValue() / 24);
            again.put("expireAtBefore", d.get("expire_at_before"));
            again.put("expireAtAfter", d.get("expire_at_after"));
            again.put("grantedBytesUnchanged", r.get("granted_bytes"));
            again.put("clientOrderKey", clientOrderKey);
            again.put("portalOrderId", portalOrderId);
            again.put("duplicated", true);
            return again;
        }

        String status = String.valueOf(r.get("status"));
        if ("reclaimed".equals(status)) {
            // 已回收的不能再续：容量已经从不池里扣掉了，单纯延长会变成白送空间
            throw new IllegalArgumentException(
                    "该套餐已到期回收，无法续费；请直接购买新套餐（续费只能延长未回收的套餐）");
        }
        java.time.OffsetDateTime before = ((java.sql.Timestamp) r.get("expire_at")).toInstant()
                .atOffset(java.time.ZoneOffset.ofHours(8));
        int hours = ((Number) r.get("period_hours")).intValue();
        // 从"原到期时间"或"现在"取较晚者为基准：已过期但未回收的，从今天起算一个周期
        java.time.OffsetDateTime base = before.isAfter(java.time.OffsetDateTime.now())
                ? before : java.time.OffsetDateTime.now();
        java.time.OffsetDateTime after = base.plusHours(hours);

        jdbc.update("UPDATE enterprise_pool_grant SET expire_at = ?, grace_end_at = ?, status = 'active', "
                + "reclaimed_at = NULL, reclaimed_deducted = false WHERE order_item_id = ?",
                after, after, orderItemId);
        if (r.get("provision_id") != null) {
            jdbc.update("UPDATE portal_provision SET expire_at = ?, status = 'active', "
                            + "quota_reclaimed = false, grace_end_at = NULL, updated_at = now() "
                            + "WHERE order_item_id = ?",
                    after, orderItemId);
        }
        // 两阶段记账 · 第二阶段：pending/orphan → applied（延长已完成）。
        // 若没有 pending 行（历史路径），退化为直接插一条 applied。
        int updated = jdbc.update(
                "UPDATE subscription_renewal SET status = 'applied', portal_order_id = ?, "
                        + "updated_at = now() WHERE client_order_key = ? AND status IN ('pending','orphan')",
                portalOrderId, clientOrderKey);
        if (updated == 0) {
            jdbc.update("INSERT INTO subscription_renewal (enterprise_id, grant_order_item_id, sku_id, quantity, "
                            + "extend_hours, expire_at_before, expire_at_after, client_order_key, portal_order_id, "
                            + "price_cents, status) VALUES (?,?,?,?,?,?,?,?,?,?,'applied') "
                            + "ON CONFLICT (client_order_key) DO NOTHING",
                    enterpriseId, orderItemId, String.valueOf(r.get("sku_id")),
                    ((Number) r.get("quantity")).intValue(), hours, before, after,
                    clientOrderKey, portalOrderId, priceCents);
        }
        recompute(enterpriseId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderItemId", orderItemId);
        out.put("skuId", r.get("sku_id"));
        out.put("quantity", ((Number) r.get("quantity")).intValue());
        out.put("extendHours", hours);
        out.put("extendDays", hours / 24);
        out.put("expireAtBefore", before);
        out.put("expireAtAfter", after);
        out.put("grantedBytesUnchanged", r.get("granted_bytes"));
        out.put("clientOrderKey", clientOrderKey);
        out.put("portalOrderId", portalOrderId);
        return out;
    }

    /**
     * 回调识别：这次 provision 是不是某笔续费单带来的？
     *
     * <p>按 (企业, sku, 数量) 匹配 30 分钟内状态为 {@code applied} 的续费记录。
     * 命中则把它标记为 {@code consumed} 并绑定 order_item_id —— 调用方据此**跳过发放**。
     *
     * @return 命中的续费记录；未命中返回 null（视为正常新购）
     */
    public Map<String, Object> consumeRenewalIfAny(long enterpriseId, String skuId, int quantity) {
        // 关键：状态必须包含 pending —— 两阶段记账下，回调很可能在"已记账、还没延长"时到达，
        // 若只认 applied，这一刻就会被当成新购买从而多发一份容量（第一版实现踩过）。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, grant_order_item_id, extend_hours, expire_at_before, expire_at_after, status "
                        + "FROM subscription_renewal "
                        + "WHERE enterprise_id = ? AND sku_id = ? AND quantity = ? "
                        + "  AND status IN ('pending','applied','orphan') "
                        + "  AND callback_consumed = FALSE "
                        + "  AND created_at > now() - interval '2 hours' "
                        + "ORDER BY id LIMIT 1",
                enterpriseId, skuId, quantity);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> hit = rows.get(0);
        // 用 callback_consumed 标记认领，而不是把 status 改成 consumed：
        // 回调可能先于延长到达，status 还得继续走 pending → applied。
        jdbc.update("UPDATE subscription_renewal SET callback_consumed = TRUE, updated_at = now() "
                + "WHERE id = ?", hit.get("id"));
        return hit;
    }

    /** 回调里把命中续费的记录与门户新 order_item 关联起来（便于对账）。 */
    public void bindRenewalToOrderItem(long renewalId, long orderItemId) {
        jdbc.update("UPDATE subscription_renewal SET matched_order_item_id = ?, updated_at = now() WHERE id = ?",
                orderItemId, renewalId);
    }


    /** 节流窗口（秒）：同一笔套餐在该窗口内只允许提交一次续费，防手抖连点。 */
    @org.springframework.beans.factory.annotation.Value("${app.enterprise.renew-throttle-seconds:3}")
    private int renewThrottleSeconds;

    /**
     * 生成续费幂等键：{@code rnw-<企业>-<订单项>-<秒级时间戳><随机>}。
     *
     * <p>2026-11-03 口径变更：**取消按天限制**。原来 key 里带 {@code yyyyMMdd}，
     * 导致同一笔套餐一天只能续一次；现在每次续费都是独立一笔（各自扣款、各自延长），
     * 防连点改由上面的秒级节流负责。
     */
    private String newRenewalKey(long enterpriseId, long orderItemId) {
        return "rnw-" + enterpriseId + "-" + orderItemId + "-"
                + java.time.Instant.now().getEpochSecond()
                + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0xFFFF));
    }
    // ── 个人域续费（2026-11-04）──────────────────────────────────
    //
    // 背景：套餐原来只有企业能续（个人域直接 400）。但个人用户也能在套餐页下单，
    // 买到的额度进自己的 quota_bytes（portal_provision.enterprise_id 为空）。
    // 这些"自己买的套餐"同样需要能续期，所以补一条个人域路径。
    //
    // 数据约定：个人域的续费记录在 subscription_renewal 里用 enterprise_id = 0 标识
    // （该列无外键，0 不会与任何企业冲突）。

    /**
     * 个人域续费 · 第一阶段：扣款前记账。
     *
     * <p>与企业版的差别：
     * <ul>
     *   <li>来源表是 {@code portal_provision}（该用户名下、enterprise_id 为空的那笔），
     *       不是 {@code enterprise_pool_grant}；</li>
     *   <li>续费只延长 {@code expire_at}，**不增加** {@code quota_bytes}
     *       （与企业池"续费不加容量"口径一致）；</li>
     *   <li>续费记录写 {@code enterprise_id = 0} 表示个人域。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> beginPersonalRenewal(long userId, long orderItemId, long priceCents) {
        Object recentRaw = jdbc.queryForMap(
                        "SELECT count(*) AS cnt FROM subscription_renewal "
                                + "WHERE enterprise_id = 0 AND grant_order_item_id = ? "
                                + "  AND created_at > now() - (? || ' seconds')::interval",
                        orderItemId, String.valueOf(renewThrottleSeconds))
                .get("cnt");
        long recent = recentRaw instanceof Number n ? n.longValue() : 0L;
        if (recent > 0) {
            throw new IllegalArgumentException(
                    "续费操作过于频繁，请 " + renewThrottleSeconds + " 秒后再试");
        }

        // 未完成的续费（扣了款但延长失败）→ 复用同一 key，避免重复扣款
        List<Map<String, Object>> unfinished = jdbc.queryForList(
                "SELECT id, client_order_key FROM subscription_renewal "
                        + "WHERE enterprise_id = 0 AND grant_order_item_id = ? "
                        + "  AND status IN ('pending','orphan') "
                        + "  AND created_at > now() - interval '30 minutes' "
                        + "ORDER BY id LIMIT 1", orderItemId);
        if (!unfinished.isEmpty()) {
            Map<String, Object> u = unfinished.get(0);
            Map<String, Object> resume = new LinkedHashMap<>();
            resume.put("chargeRequired", true);
            resume.put("resumed", true);
            resume.put("clientOrderKey", u.get("client_order_key"));
            resume.put("renewalId", u.get("id"));
            log.info("[renew] 个人域复用未完成续费 key={}", u.get("client_order_key"));
            return resume;
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pp.order_item_id, pp.sku_id, pp.quantity, pp.expire_at, pp.status, "
                        + "       COALESCE(pp.period_hours, 720) AS period_hours "
                        + "FROM portal_provision pp "
                        + "WHERE pp.order_item_id = ? AND pp.local_user_id = ? "
                        + "  AND (pp.enterprise_id IS NULL OR pp.enterprise_id = 0)",
                orderItemId, userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到该套餐：" + orderItemId + "（只能续费自己购买的套餐）");
        }
        Map<String, Object> r = rows.get(0);
        // 个人域的 portal_provision.status 只有 pending/granting/active/grace/expired/failed，
        // **没有 reclaimed**（这是企业池 grant 才有的状态）。
        // 只有 failed（开通失败）才不允许续费；已 expired 的仍可续（从当前时间起算）。
        String st = String.valueOf(r.get("status"));
        if ("failed".equals(st)) {
            throw new IllegalArgumentException("该套餐开通失败，无法续费；请重新购买");
        }
        if (r.get("expire_at") == null) {
            throw new IllegalArgumentException("该套餐没有到期时间，无法续费（请联系管理员核对开通记录）");
        }

        java.time.OffsetDateTime before = ((java.sql.Timestamp) r.get("expire_at")).toInstant()
                .atOffset(java.time.ZoneOffset.ofHours(8));
        int hours = ((Number) r.get("period_hours")).intValue();
        java.time.OffsetDateTime base = before.isAfter(java.time.OffsetDateTime.now())
                ? before : java.time.OffsetDateTime.now();
        java.time.OffsetDateTime after = base.plusHours(hours);

        String clientOrderKey = newRenewalKey(0L, orderItemId);
        jdbc.update("INSERT INTO subscription_renewal (enterprise_id, grant_order_item_id, sku_id, "
                        + "quantity, extend_hours, expire_at_before, expire_at_after, client_order_key, "
                        + "price_cents, status) VALUES (0,?,?,?,?,?,?,?,?,'pending') "
                        + "ON CONFLICT (client_order_key) DO NOTHING",
                orderItemId, String.valueOf(r.get("sku_id")),
                ((Number) r.get("quantity")).intValue(), hours, before, after,
                clientOrderKey, priceCents);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chargeRequired", true);
        out.put("clientOrderKey", clientOrderKey);
        out.put("orderItemId", orderItemId);
        out.put("skuId", r.get("sku_id"));
        out.put("quantity", ((Number) r.get("quantity")).intValue());
        out.put("extendHours", hours);
        out.put("expireAtBefore", before);
        out.put("expireAtAfter", after);
        log.info("[renew] 个人域已记账(pending) userId={} item={} key={} 到期 {} → {}",
                userId, orderItemId, clientOrderKey, before, after);
        return out;
    }

    /** 个人域续费 · 第二阶段：扣款成功后延长到期时间（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyPersonalRenewal(long userId, long orderItemId,
                                                    String clientOrderKey, String portalOrderId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, status, expire_at_after, extend_hours FROM subscription_renewal "
                        + "WHERE client_order_key = ?", clientOrderKey);
        String st = String.valueOf(row.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderItemId", orderItemId);
        out.put("clientOrderKey", clientOrderKey);

        if ("applied".equals(st)) {
            out.put("expireAtAfter", row.get("expire_at_after"));
            out.put("extendHours", row.get("extend_hours"));
            out.put("duplicated", true);
            return out;
        }

        java.time.OffsetDateTime after = ((java.sql.Timestamp) row.get("expire_at_after")).toInstant()
                .atOffset(java.time.ZoneOffset.ofHours(8));
        int updated = jdbc.update(
                "UPDATE portal_provision SET expire_at = ? WHERE order_item_id = ? AND local_user_id = ? "
                        + "AND (enterprise_id IS NULL OR enterprise_id = 0)",
                after, orderItemId, userId);
        if (updated == 0) {
            throw new IllegalStateException("延长失败：找不到该用户名下的开通记录 " + orderItemId);
        }
        jdbc.update("UPDATE subscription_renewal SET status = 'applied', portal_order_id = ?, "
                + "updated_at = now() WHERE client_order_key = ?", portalOrderId, clientOrderKey);

        out.put("expireAtAfter", after);
        out.put("extendHours", row.get("extend_hours"));
        out.put("duplicated", false);
        log.info("[renew] 个人域续费完成 userId={} item={} 新到期={}", userId, orderItemId, after);
        return out;
    }

    /** 个人域：我自己买的套餐列表（用于套餐页展示与续费入口）。 */
    public List<Map<String, Object>> personalSubscriptions(long userId) {
        return jdbc.queryForList(
                "SELECT pp.order_item_id AS \"orderItemId\", "
                        + "       pp.sku_id AS \"skuId\", "
                        + "       COALESCE(s.name, pp.sku_id) AS \"skuName\", "
                        + "       COALESCE(pp.quantity, 1) AS \"quantity\", "
                        + "       COALESCE(s.quota_gb, 0) AS \"unitQuotaGb\", "
                        + "       pp.granted_bytes AS \"grantedBytes\", "
                        + "       COALESCE(s.price_cents, 0) AS \"priceCents\", "
                        + "       COALESCE(pp.period_hours, 720) AS \"periodHours\", "
                        + "       COALESCE(pp.period_hours, 720) / 24 AS \"periodDays\", "
                        + "       pp.created_at AS \"createdAt\", "
                        + "       pp.status AS \"status\", "
                        + "       pp.expire_at AS \"expireAt\", "
                        + "       (SELECT count(*) FROM subscription_renewal sr "
                        + "         WHERE sr.enterprise_id = 0 AND sr.grant_order_item_id = pp.order_item_id "
                        + "           AND sr.status = 'applied') AS \"renewals\", "
                        + "       (pp.expire_at IS NOT NULL AND pp.expire_at < now()) AS \"expired\" "
                        + "FROM portal_provision pp "
                        + "LEFT JOIN portal_product_sku s ON s.sku_id = pp.sku_id "
                        + "WHERE pp.local_user_id = ? AND (pp.enterprise_id IS NULL OR pp.enterprise_id = 0) "
                        + "ORDER BY pp.order_item_id DESC", userId);
    }
    // ── 两阶段记账 · 第一阶段：扣款前记账 ────────────────────────

    /**
     * 续费第一阶段：在扣款**之前**落一条 {@code pending} 记录。
     *
     * <p>为什么必须先记账：扣款是外部 HTTP 调用、不可回滚。若先扣款再记账，
     * 一旦记账失败，门户随后回调 {@code /internal/provision} 时查不到续费记录，
     * 就会把这笔当成【新购买】发放容量 —— 付一次钱拿到两份收益（真实踩过）。
     * 先落 pending 后，即使紧接着崩溃，回调也能看到 pending 从而跳过发放；
     * 重试则沿用同一 {@code clientOrderKey}，门户返回 3003 不会重复扣款。
     *
     * @return {@code chargeRequired=false} 表示这笔已经完成过（applied/consumed），
     *         调用方**不要再扣款**，把 {@code existing} 返回给前端即可
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> beginRenewal(long enterpriseId, long orderItemId, long priceCents) {
        // ── 节流（2026-11-03 口径变更）──────────────────────────────
        // 取消"同一笔每天只能续一次"，改为**按秒节流**，防的是手抖连点，不是业务限制。
        // 同一天可以反复续费（每次真实扣款、每次延长一个周期）。
        // 注意：PostgreSQL 的 count(*) 返回 Long，不能直接强转 Integer
        Object recentRaw = jdbc.queryForMap(
                        "SELECT count(*) AS cnt FROM subscription_renewal "
                                + "WHERE enterprise_id = ? AND grant_order_item_id = ? "
                                + "  AND created_at > now() - (? || ' seconds')::interval",
                        enterpriseId, orderItemId, String.valueOf(renewThrottleSeconds))
                .get("cnt");
        long recent = recentRaw instanceof Number n ? n.longValue() : 0L;
        if (recent > 0) {
            throw new IllegalArgumentException(
                    "续费操作过于频繁，请 " + renewThrottleSeconds + " 秒后再试");
        }

        // ── 未完成的续费（扣了款但延长失败）→ 复用它的 key 继续做 ─────────
        // 必须复用同一个 clientOrderKey：门户对重复 key 返回 3003 不再扣款，
        // 否则重试会变成一笔新订单、再扣一次钱。
        List<Map<String, Object>> unfinished = jdbc.queryForList(
                "SELECT id, client_order_key, status, expire_at_before, expire_at_after, extend_hours "
                        + "FROM subscription_renewal "
                        + "WHERE enterprise_id = ? AND grant_order_item_id = ? "
                        + "  AND status IN ('pending','orphan') "
                        + "  AND created_at > now() - interval '30 minutes' "
                        + "ORDER BY id LIMIT 1", enterpriseId, orderItemId);
        if (!unfinished.isEmpty()) {
            Map<String, Object> u = unfinished.get(0);
            Map<String, Object> resume = new LinkedHashMap<>();
            resume.put("chargeRequired", true);
            resume.put("resumed", true);
            resume.put("clientOrderKey", u.get("client_order_key"));
            resume.put("renewalId", u.get("id"));
            log.info("[renew] 复用未完成的续费记录 key={} status={}（避免重复扣款）",
                    u.get("client_order_key"), u.get("status"));
            return resume;
        }

        String clientOrderKey = newRenewalKey(enterpriseId, orderItemId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.order_item_id, g.expire_at, g.status, g.granted_bytes, "
                        + "       COALESCE(pp.sku_id, '') AS sku_id, "
                        + "       COALESCE(pp.quantity, 1) AS quantity, "
                        + "       COALESCE(pp.period_hours, 720) AS period_hours "
                        + "FROM enterprise_pool_grant g "
                        + "LEFT JOIN portal_provision pp ON pp.order_item_id = g.order_item_id "
                        + "WHERE g.enterprise_id = ? AND g.order_item_id = ?",
                enterpriseId, orderItemId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到该套餐：" + orderItemId);
        }
        Map<String, Object> r = rows.get(0);
        if ("reclaimed".equals(String.valueOf(r.get("status")))) {
            throw new IllegalArgumentException(
                    "该套餐已到期回收，无法续费；请直接购买新套餐（续费只能延长未回收的套餐）");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> done = jdbc.queryForList(
                "SELECT id, status, expire_at_before, expire_at_after, extend_hours "
                        + "FROM subscription_renewal WHERE client_order_key = ?", clientOrderKey);
        if (!done.isEmpty()) {
            Map<String, Object> d = done.get(0);
            String st = String.valueOf(d.get("status"));
            out.put("orderItemId", orderItemId);
            out.put("skuId", r.get("sku_id"));
            out.put("quantity", ((Number) r.get("quantity")).intValue());
            out.put("grantedBytesUnchanged", r.get("granted_bytes"));
            if ("applied".equals(st) || "consumed".equals(st)) {
                // 已经完成：不要再扣款
                out.put("chargeRequired", false);
                out.put("existing", d);
                return out;
            }
            // pending / orphan：继续用同一个 key 扣款（门户幂等，不会重复扣）
            log.info("[renew] 命中未完成续费记录 status={} key={}，继续扣款（门户幂等）", st, clientOrderKey);
            out.put("chargeRequired", true);
            out.put("renewalId", d.get("id"));
            out.put("expireAtBefore", d.get("expire_at_before"));
            out.put("expireAtAfter", d.get("expire_at_after"));
            return out;
        }

        java.time.OffsetDateTime before = ((java.sql.Timestamp) r.get("expire_at")).toInstant()
                .atOffset(java.time.ZoneOffset.ofHours(8));
        int hours = ((Number) r.get("period_hours")).intValue();
        java.time.OffsetDateTime base = before.isAfter(java.time.OffsetDateTime.now())
                ? before : java.time.OffsetDateTime.now();
        java.time.OffsetDateTime after = base.plusHours(hours);

        jdbc.update("INSERT INTO subscription_renewal (enterprise_id, grant_order_item_id, sku_id, "
                        + "quantity, extend_hours, expire_at_before, expire_at_after, client_order_key, "
                        + "price_cents, status) VALUES (?,?,?,?,?,?,?,?,?,'pending') "
                        + "ON CONFLICT (client_order_key) DO NOTHING",
                enterpriseId, orderItemId, String.valueOf(r.get("sku_id")),
                ((Number) r.get("quantity")).intValue(), hours, before, after,
                clientOrderKey, priceCents);

        out.put("chargeRequired", true);
        out.put("orderItemId", orderItemId);
        out.put("skuId", r.get("sku_id"));
        out.put("quantity", ((Number) r.get("quantity")).intValue());
        out.put("expireAtBefore", before);
        out.put("expireAtAfter", after);
        out.put("clientOrderKey", clientOrderKey);
        log.info("[renew] 已记账(pending) ent={} item={} key={} 到期 {} → {}",
                enterpriseId, orderItemId, clientOrderKey, before, after);
        return out;
    }
    // ── 可用额度 / 超卖 ───────────────────────────────────────

    /**
     * 企业当前「可分配额度」= {@code totalBytes − allocatedBytes}。
     *
     * <p>为负 = 可分配的比已分配的少（超卖）：说明池子缩水了（到期回收），
     * 但分给成员的额度还没收回。
     */
    public long availableBytes(long enterpriseId) {
        Map<String, Object> p = pool(enterpriseId);
        return (Long) p.get("totalBytes") - (Long) p.get("allocatedBytes");
    }

    /**
     * 该企业是否因超额而**停用用户端**。
     *
     * <p>规则（2026-10-30）：可分配额度为负 → 该企业所有账号不能登录用户端；
     * 企业管理员仍可进管理端把分配降下来，恢复正常后自动解禁。
     *
     * <p>只看企业；个人域用户（{@code enterprise_id = 0}）永远返回 false。
     */
    public boolean suspendedForOversell(long enterpriseId) {
        return enterpriseId > 0 && availableBytes(enterpriseId) < 0;
    }

    /** 全部企业重算（定时校准，修正冗余列漂移）。 */
    public int recalibrateAll() {
        List<Long> ents = jdbc.queryForList("SELECT enterprise_id FROM enterprise_pool", Long.class);
        for (Long e : ents) {
            recompute(e);
        }
        return ents.size();
    }

    // ── 工具 ─────────────────────────────────────────────────

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 人读的容量：>= 1GB 显示 GB，否则显示 MB（避免 512MB 被截成 "0 GB"）。 */
    private static String gb(long bytes) {
        long g = 1024L * 1024 * 1024;
        if (Math.abs(bytes) >= g) {
            return (bytes / g) + " GB";
        }
        return (bytes / (1024 * 1024)) + " MB";
    }
}
