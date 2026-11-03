package com.company.cloud.files.stats.mapper;

import com.company.cloud.files.stats.dto.AdminStatsOverview;
import com.company.cloud.files.stats.dto.TopUser;
import com.company.cloud.files.stats.dto.TrafficPoint;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理端大盘统计 SQL（R-C09）。
 *
 * <p>跨模块只读：quota_bytes / used_bytes 归 A 组 users 表，extra_bytes（计费增量额度，字节）归 D 组 billing 维护，audit_logs 归 C 组。
 * 总额度 = quota_bytes + extra_bytes + enterprise_allocated_bytes
 *        （免费 + 计费增量 + 管理员分配的企业额度）。
 *        2026-11-03 修正：此前漏算 enterprise_allocated_bytes，
 *        企业买了套餐、管理员把额度分给成员后，统计大盘的"总配额"仍只显示每人 1GB 免费额度。
 * 统一用本 Mapper 直查表而非引入跨模块 Repository 依赖，与 AuditLogMapper.selectUsernames 同模式。
 */
@Mapper
public interface AdminStatsMapper {

    /** 全平台配额 / 用量 / 用户数 */
    @ConstructorArgs({
            @Arg(column = "total_quota_bytes", javaType = long.class),
            @Arg(column = "used_bytes", javaType = long.class),
            @Arg(column = "remaining_bytes", javaType = long.class),
            @Arg(column = "user_count", javaType = long.class)
    })
    /**
     * 全平台总览。
     *
     * @param entId {@code null} = 全平台（平台管理员）；非 null = 只看该企业（企业管理员）
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(quota_bytes + extra_bytes + COALESCE(enterprise_allocated_bytes, 0)), 0) AS total_quota_bytes,
                   COALESCE(SUM(used_bytes), 0) AS used_bytes,
                   GREATEST(COALESCE(SUM(quota_bytes + extra_bytes + COALESCE(enterprise_allocated_bytes, 0)), 0)
                          - COALESCE(SUM(used_bytes), 0), 0) AS remaining_bytes,
                   COUNT(*) AS user_count
            FROM users
            <where>
              <if test="entId != null">enterprise_id = #{entId}</if>
            </where>
            </script>
            """)
    AdminStatsOverview selectAdminOverview(@Param("entId") Long entId);

    /** 用量 Top10 用户（并列时按 id 稳定排序） */
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = long.class),
            @Arg(column = "username", javaType = String.class),
            @Arg(column = "used_bytes", javaType = long.class),
            @Arg(column = "quota_bytes", javaType = long.class),
            @Arg(column = "nickname", javaType = String.class)
    })
    @Select("""
            <script>
            SELECT id          AS user_id,
                   username,
                   used_bytes,
                   quota_bytes + extra_bytes + COALESCE(enterprise_allocated_bytes, 0) AS quota_bytes,
                   nickname
            FROM users
            <where>
              <if test="entId != null">enterprise_id = #{entId}</if>
            </where>
            ORDER BY used_bytes DESC, id ASC
            LIMIT 10
            </script>
            """)
    List<TopUser> selectTopUsers(@Param("entId") Long entId);

    /**
     * 近 N 日上传 / 下载流量（audit_logs 按日聚合）。
     *
     * <p>依赖契约：upload / download 审计记录的 detail JSON 含 {"size": 字节数}。
     * B 组 UploadService 尚未埋点，当前查询结果为空；埋点补齐后本接口自动出数，无需改动。
     * 无 size 的记录按 0 计入当日。
     */
    @ConstructorArgs({
            @Arg(column = "day", javaType = LocalDate.class),
            @Arg(column = "upload_bytes", javaType = long.class),
            @Arg(column = "download_bytes", javaType = long.class)
    })
    @Select("""
            <script>
            SELECT created_at::date AS day,
                   COALESCE(SUM(CASE WHEN action = 'upload'
                                     THEN (detail->>'size')::bigint END), 0) AS upload_bytes,
                   COALESCE(SUM(CASE WHEN action = 'download'
                                     THEN (detail->>'size')::bigint END), 0) AS download_bytes
            FROM audit_logs
            WHERE action IN ('upload', 'download')
              AND created_at >= CURRENT_DATE - (#{days}::int - 1)
              <if test="entId != null">AND enterprise_id = #{entId}</if>
            GROUP BY day
            ORDER BY day
            </script>
            """)
    List<TrafficPoint> selectTraffic(@Param("days") int days, @Param("entId") Long entId);
}
