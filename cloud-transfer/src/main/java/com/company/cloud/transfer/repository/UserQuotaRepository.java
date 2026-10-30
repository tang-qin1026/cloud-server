package com.company.cloud.transfer.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * 配额操作——users.used_bytes 由 B 组增减维护（任务书 CS-DOC-03 §7）。
 *
 * <p><b>docs/31 企业资源分配：上传是"双闸"</b>
 * <ol>
 *   <li><b>闸 1（个人）</b>：{@code used + 本次 ≤ 免费 + 个人增量 + 企业分配}。
 *       防单个成员吃掉整个企业空间。</li>
 *   <li><b>闸 2（企业池）</b>：本次要动用到"企业分配"那部分时，
 *       还要 {@code pool.total − pool.used ≥ 本次}。防超卖（分配写错 / 到期导致池缩小）。</li>
 * </ol>
 *
 * <p>实现上闸 1 在条件 UPDATE 里原子完成（PG 行锁），闸 2 用一次独立查询；
 * 池数据读 {@code enterprise_pool} 冗余列，不实时 SUM(files)。
 */
@Repository
public class UserQuotaRepository {

    @PersistenceContext
    private EntityManager em;

    /** 总额度 = 免费额度 + 个人增量额度 + **企业分配额度**。 */
    private static final String TOTAL_EXPR =
            "quota_bytes + extra_bytes + COALESCE(enterprise_allocated_bytes, 0)";

    /** 预检：只读判断会不会超配额（供 init 快速拒绝，不加锁）。 */
    public boolean checkAvailable(long userId, long delta) {
        Long remaining = remaining(userId);
        return remaining != null && remaining >= delta;
    }

    /** 个人侧剩余额度（含企业分配）。查不到用户返回 null。 */
    public Long remaining(long userId) {
        try {
            return (Long) em.createNativeQuery(
                            "SELECT " + TOTAL_EXPR + " - used_bytes FROM users WHERE id = :id")
                    .setParameter("id", userId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /** 不含企业分配的个人剩余（免费 + 个人增量 − 已用）。用于判断"本次是否要动用到企业分配"。 */
    public long personalRemaining(long userId) {
        try {
            Long v = (Long) em.createNativeQuery(
                            "SELECT quota_bytes + extra_bytes - used_bytes FROM users WHERE id = :id")
                    .setParameter("id", userId)
                    .getSingleResult();
            return v == null ? 0L : v;
        } catch (NoResultException e) {
            return 0L;
        }
    }

    /**
     * 企业池剩余（{@code total − used}）。
     *
     * @return 无企业 / 池不存在时返回 {@code -1}（= 不启用闸 2，保持个人侧行为）
     */
    public long enterprisePoolRemaining(long userId) {
        try {
            Long v = (Long) em.createNativeQuery(
                            "SELECT p.total_bytes - p.used_bytes FROM users u "
                                    + "JOIN enterprise_pool p ON p.enterprise_id = u.enterprise_id "
                                    + "WHERE u.id = :id AND u.enterprise_id > 0")
                    .setParameter("id", userId)
                    .getSingleResult();
            return v == null ? -1L : v;
        } catch (NoResultException e) {
            return -1L;
        }
    }

    /**
     * 实扣配额（complete 时）——条件 UPDATE 原子防超卖。
     */
    public boolean tryReserve(long userId, long delta) {
        int updated = em.createNativeQuery(
                        "UPDATE users SET used_bytes = used_bytes + :delta, updated_at = now() "
                                + "WHERE id = :id AND status = 'active' AND used_bytes + :delta <= "
                                + TOTAL_EXPR)
                .setParameter("delta", delta)
                .setParameter("id", userId)
                .executeUpdate();
        return updated == 1;
    }

    /**
     * 动用到企业分配时，同步累加企业池已用（冗余列，5 分钟校准任务会修正）。
     *
     * <p>只加不减：减在 {@link #release}。
     */
    public void addPoolUsed(long userId, long delta) {
        em.createNativeQuery(
                        "UPDATE enterprise_pool p SET used_bytes = p.used_bytes + :delta, updated_at = now() "
                                + "FROM users u WHERE u.id = :id AND u.enterprise_id = p.enterprise_id "
                                + "AND u.enterprise_id > 0")
                .setParameter("delta", delta)
                .setParameter("id", userId)
                .executeUpdate();
    }

    /** 释放配额（彻底删除 / abort 回滚时），同时回退企业池已用。 */
    public void release(long userId, long delta) {
        em.createNativeQuery(
                        "UPDATE users SET used_bytes = GREATEST(0, used_bytes - :delta), updated_at = now() "
                                + "WHERE id = :id")
                .setParameter("delta", delta)
                .setParameter("id", userId)
                .executeUpdate();
        em.createNativeQuery(
                        "UPDATE enterprise_pool p SET used_bytes = GREATEST(0, p.used_bytes - :delta), updated_at = now() "
                                + "FROM users u WHERE u.id = :id AND u.enterprise_id = p.enterprise_id "
                                + "AND u.enterprise_id > 0")
                .setParameter("delta", delta)
                .setParameter("id", userId)
                .executeUpdate();
    }
    /**
     * 该用户所属企业是否**已进入宽限期**（docs/31 §7.1：到期后 7 天内只读）。
     *
     * <p>判定：企业**有**购买记录，但**没有任何一笔**仍在 {@code active}。
     * 换句话说"全部套餐都到期了"。
     *
     * <p>为什么不是"存在任意 grace 就禁"：企业可能同时有多笔（月中加购），
     * 只要还有一笔有效，空间就是有效的，不该因为另一笔到期就禁上传。
     *
     * @return true = 宽限期内（禁止上传，可读可下载）
     */
    public boolean enterpriseInGraceOnly(long userId) {
        try {
            Integer v = (Integer) em.createNativeQuery(
                            "SELECT CASE "
                                    + "  WHEN NOT EXISTS (SELECT 1 FROM enterprise_pool_grant g "
                                    + "                     WHERE g.enterprise_id = u.enterprise_id) THEN 0 "
                                    + "  WHEN EXISTS (SELECT 1 FROM enterprise_pool_grant g "
                                    + "                WHERE g.enterprise_id = u.enterprise_id "
                                    + "                  AND g.status = 'active') THEN 0 "
                                    + "  ELSE 1 END "
                                    + "FROM users u WHERE u.id = :id AND u.enterprise_id > 0")
                    .setParameter("id", userId)
                    .getSingleResult();
            return v != null && v == 1;
        } catch (NoResultException e) {
            return false;   // 个人域用户：不适用
        }
    }
}