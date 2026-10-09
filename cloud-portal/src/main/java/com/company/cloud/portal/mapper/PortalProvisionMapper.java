package com.company.cloud.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.cloud.portal.entity.PortalProvision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * portal_provision 数据访问：开通幂等状态机 + 配额授予/回收。
 *
 * <p>所有状态迁移都是**条件更新**（WHERE 带旧状态），天然幂等、可重入，
 * 且并发下只有一方能抢到，避免重复开通 / 重复授予。
 */
@Mapper
public interface PortalProvisionMapper extends BaseMapper<PortalProvision> {

    /** 幂等查询：按门户订单项 id 取首次开通记录。 */
    @Select("SELECT * FROM portal_provision WHERE order_item_id = #{orderItemId}")
    PortalProvision selectByOrderItemId(@Param("orderItemId") Long orderItemId);

    /**
     * 抢占开通权：pending → granting。
     * 返回 1 表示本次调用赢得授予权；返回 0 表示已被并发请求抢走或状态已推进。
     */
    @Update("""
            UPDATE portal_provision
            SET status = 'granting', updated_at = now()
            WHERE order_item_id = #{orderItemId} AND status = 'pending'
            """)
    int claim(@Param("orderItemId") Long orderItemId);

    /** 完成开通：granting → active，写入实例号与到期时刻。 */
    @Update("""
            UPDATE portal_provision
            SET status = 'active',
                instance_id = #{instanceId},
                expire_at = #{expireAt},
                grace_end_at = NULL,
                updated_at = now()
            WHERE order_item_id = #{orderItemId} AND status = 'granting'
            """)
    int finalizeActive(@Param("orderItemId") Long orderItemId,
                       @Param("instanceId") String instanceId,
                       @Param("expireAt") OffsetDateTime expireAt);

    /** 建实例失败：pending/granting → failed（门户会按 5001 重试或退款）。 */
    @Update("""
            UPDATE portal_provision
            SET status = 'failed', updated_at = now()
            WHERE order_item_id = #{orderItemId} AND status IN ('pending', 'granting')
            """)
    int markFailed(@Param("orderItemId") Long orderItemId);

    /** 授予配额：users.quota_bytes += bytes（开通即扩容，与上传配额判定口径一致）。 */
    @Update("""
            UPDATE users
            SET quota_bytes = quota_bytes + #{bytes}, updated_at = now()
            WHERE id = #{userId}
            """)
    int grantQuota(@Param("userId") Long userId, @Param("bytes") long bytes);

    /** 回收配额（到期停机）：GREATEST 防负。 */
    @Update("""
            UPDATE users
            SET quota_bytes = GREATEST(quota_bytes - #{bytes}, 0), updated_at = now()
            WHERE id = #{userId}
            """)
    int reclaimQuota(@Param("userId") Long userId, @Param("bytes") long bytes);

    /** 到期扫描：批量把 active 且已到期置为 grace，并算出宽限期结束时刻（幂等）。 */
    @Update("""
            UPDATE portal_provision
            SET status = 'grace',
                grace_end_at = expire_at + (#{graceDays} * INTERVAL '1 day'),
                updated_at = now()
            WHERE status = 'active'
              AND expire_at IS NOT NULL AND expire_at <= #{now}
            """)
    int markDueAsGrace(@Param("now") OffsetDateTime now, @Param("graceDays") int graceDays);

    /** 宽限期结束的候选记录（逐条回收配额）。 */
    @Select("""
            SELECT * FROM portal_provision
            WHERE status = 'grace' AND grace_end_at IS NOT NULL AND grace_end_at <= #{now}
            ORDER BY id
            """)
    List<PortalProvision> selectGraceDue(@Param("now") OffsetDateTime now);

    /**
     * 停机：grace → expired。条件更新保证同一记录只有一次成功，
     * 只有返回 1 的调用方才去回收配额（回收幂等的关键）。
     */
    @Update("""
            UPDATE portal_provision
            SET status = 'expired', quota_reclaimed = TRUE, updated_at = now()
            WHERE id = #{id} AND status = 'grace'
            """)
    int markExpired(@Param("id") Long id);

    /** 按量计费候选：active 且（从未出账 或 已超过一个计费周期）。 */
    @Select("""
            SELECT * FROM portal_provision
            WHERE status = 'active'
              AND (last_billed_at IS NULL
                   OR last_billed_at <= #{now} - (period_days * INTERVAL '1 day'))
            ORDER BY id
            LIMIT #{limit}
            """)
    List<PortalProvision> selectPostpaidDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** 按量出账成功后打标，避免重复出账。 */
    @Update("""
            UPDATE portal_provision
            SET last_billed_at = #{billedAt}, updated_at = now()
            WHERE id = #{id}
            """)
    int markBilled(@Param("id") Long id, @Param("billedAt") OffsetDateTime billedAt);
}
