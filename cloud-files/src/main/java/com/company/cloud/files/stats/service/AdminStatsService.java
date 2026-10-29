package com.company.cloud.files.stats.service;

import com.company.cloud.files.stats.dto.AdminStatsOverview;
import com.company.cloud.files.stats.dto.TopUser;
import com.company.cloud.files.stats.dto.TrafficPoint;

import java.util.List;

/**
 * 管理端大盘统计（R-C09）：全平台总览、用量 Top10、近 N 日流量曲线。
 */
public interface AdminStatsService {

    /**
     * 总览。
     *
     * @param entId {@code null} = 全平台（平台管理员）；非 null = 只看该企业（企业管理员）
     */
    AdminStatsOverview overview(Long entId);

    /** 用量 Top10 用户；{@code entId} 语义同 {@link #overview(Long)}。 */
    List<TopUser> topUsers(Long entId);

    /** 近 days 日上传/下载流量（days 收敛到 1~90，无流量的日期补零点，便于前端画曲线） */
    List<TrafficPoint> traffic(int days, Long entId);
}
