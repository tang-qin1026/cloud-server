package com.company.cloud.billing.mapper;

import com.company.cloud.billing.dto.UserQuotaView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/**
 * users 表跨模块只读 + 冗余列维护（Owner：A 组，但 extra 两列仅 D 组 billing 写，§3.2）。
 * 与 AdminStatsMapper 同样的跨模块直查模式，避免引入跨模块 Repository 依赖。
 */
@Mapper
public interface UserQuotaMapper {

    /**
     * 读取用户免费额度 / 已用量 / 增量冗余值（billing/quota 与上传判定用）。
     */
    @Select("""
            SELECT id, username, quota_bytes AS free_bytes, used_bytes, extra_bytes, extra_expire_at,
                   COALESCE(enterprise_allocated_bytes, 0) AS enterprise_allocated_bytes
            FROM users
            WHERE id = #{userId}
            """)
    UserQuotaView selectByUserId(@Param("userId") Long userId);

    /**
     * 审批通过时冗余同步（§4.2 approve 第③步）：
     *   extra_bytes += gb_count
     *   extra_expire_at = LEAST(现有值, 新 expire_at)（null 视为取新值）
     */
    @Update("""
            UPDATE users
            SET extra_bytes = extra_bytes + #{gbCount},
                extra_expire_at = LEAST(COALESCE(extra_expire_at, #{expireAt}), #{expireAt}),
                updated_at = now()
            WHERE id = #{userId}
            """)
    int addExtra(@Param("userId") Long userId,
                 @Param("gbCount") long gbCount,
                 @Param("expireAt") OffsetDateTime expireAt);

    /**
     * 取用户名（管理端列表 JOIN 用；跨模块只读）。
     */
    @Select("SELECT username FROM users WHERE id = #{userId}")
    String selectUsername(@Param("userId") Long userId);
}