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
        return jdbc.queryForList(
                "SELECT g.order_item_id AS \"orderItemId\", "
                        + "       g.granted_bytes AS \"grantedBytes\", "
                        + "       g.status AS \"status\", "
                        + "       g.expire_at AS \"expireAt\", "
                        + "       g.reclaimed_at AS \"reclaimedAt\", "
                        + "       COALESCE(pp.sku_id, '') AS \"skuId\", "
                        + "       p.name AS \"skuName\", "
                        + "       p.price_cents AS \"priceCents\", "
                        + "       p.quota_gb AS \"quotaGb\" "
                        + "FROM enterprise_pool_grant g "
                        + "LEFT JOIN portal_provision pp ON pp.order_item_id = g.order_item_id "
                        + "LEFT JOIN portal_product_sku p ON p.sku_id = pp.sku_id "
                        + "WHERE g.enterprise_id = ? "
                        + "ORDER BY g.expire_at DESC, g.id DESC", enterpriseId);
    }

    /**
     * 续费某笔套餐：查出当初买的 SKU 与单价，**新下一单**（空间是预付制，续费 = 再买一期）。
     *
     * <p>本方法只负责算出「要买什么」，实际下单由 controller 走 docs/30 的服务端下单
     * （下单需要门户客户端，在 cloud-portal，而本服务在 cloud-auth）。
     *
     * @return skuId / skuName / quotaGb / priceCents / clientOrderKey
     */
    public Map<String, Object> renew(long enterpriseId, long orderItemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT g.order_item_id, g.status, pp.sku_id, p.name, p.quota_gb, p.price_cents "
                        + "FROM enterprise_pool_grant g "
                        + "LEFT JOIN portal_provision pp ON pp.order_item_id = g.order_item_id "
                        + "LEFT JOIN portal_product_sku p ON p.sku_id = pp.sku_id "
                        + "WHERE g.enterprise_id = ? AND g.order_item_id = ?", enterpriseId, orderItemId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到该套餐：" + orderItemId);
        }
        Map<String, Object> r = rows.get(0);
        Object sku = r.get("sku_id");
        if (sku == null || String.valueOf(sku).isBlank()) {
            throw new IllegalArgumentException("该套餐查不到对应的商品，无法续费（请直接购买新套餐）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderItemId", orderItemId);
        out.put("skuId", String.valueOf(sku));
        out.put("skuName", r.get("name"));
        out.put("quotaGb", r.get("quota_gb"));
        out.put("priceCents", r.get("price_cents"));
        out.put("clientOrderKey", renewClientOrderKey(enterpriseId, orderItemId));
        out.put("previousStatus", r.get("status"));
        return out;
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
