package com.company.cloud.auth.enterprise;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    public Map<String, Object> applyRenewal(long enterpriseId, long orderItemId,
                                            String clientOrderKey, String portalOrderId,
                                            long priceCents) {
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

        // ── 幂等（重要）：这个 clientOrderKey 已经续过一次了就直接返回旧结果 ──
        //
        // 门户对重复 clientOrderKey 返回 3003（duplicated=true，**本次没扣款**）。
        // 若此处再延长一次，就会出现"只扣一次钱、到期时间被推 N 次"——
        // 真实踩过：同一笔 355 连点 4 次，到期从 2026-12-02 被推到 2027-04-01，
        // 但钱包只扣了 1 期（100 元）。
        // 门户的幂等是对的，这一段是为了让**延长**也幂等。
        List<Map<String, Object>> done = jdbc.queryForList(
                "SELECT id, expire_at_before, expire_at_after, extend_hours "
                        + "FROM subscription_renewal WHERE client_order_key = ?", clientOrderKey);
        if (!done.isEmpty()) {
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
        jdbc.update("INSERT INTO subscription_renewal (enterprise_id, grant_order_item_id, sku_id, quantity, "
                        + "extend_hours, expire_at_before, expire_at_after, client_order_key, portal_order_id, "
                        + "price_cents, status) VALUES (?,?,?,?,?,?,?,?,?,?,'applied') "
                        + "ON CONFLICT (client_order_key) DO NOTHING",
                enterpriseId, orderItemId, String.valueOf(r.get("sku_id")),
                ((Number) r.get("quantity")).intValue(), hours, before, after,
                clientOrderKey, portalOrderId, priceCents);
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
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, grant_order_item_id, extend_hours, expire_at_before, expire_at_after "
                        + "FROM subscription_renewal "
                        + "WHERE enterprise_id = ? AND sku_id = ? AND quantity = ? AND status = 'applied' "
                        + "  AND created_at > now() - interval '2 hours' "
                        + "ORDER BY id LIMIT 1",
                enterpriseId, skuId, quantity);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> hit = rows.get(0);
        jdbc.update("UPDATE subscription_renewal SET status = 'consumed', updated_at = now() "
                + "WHERE id = ?", hit.get("id"));
        return hit;
    }

    /** 回调里把命中续费的记录与门户新 order_item 关联起来（便于对账）。 */
    public void bindRenewalToOrderItem(long renewalId, long orderItemId) {
        jdbc.update("UPDATE subscription_renewal SET matched_order_item_id = ?, updated_at = now() WHERE id = ?",
                orderItemId, renewalId);
    }
    /**
     * 续费幂等键：{@code ent-<企业>-<订单项>-<yyyyMMdd>}。
     *
     * <p>同一天对同一笔重复点击只成功一次（门户按 clientOrderKey 幂等），
     * 避免手抖连点扣两次钱；换天再点视为新的一期，是合理的。
     */
    public static String renewClientOrderKey(long enterpriseId, long orderItemId) {
        return "ent-" + enterpriseId + "-" + orderItemId + "-"
                + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
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
