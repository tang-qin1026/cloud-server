package com.company.cloud.files.stats.service.impl;

import com.company.cloud.files.stats.dto.AdminStatsOverview;
import com.company.cloud.files.stats.dto.TopUser;
import com.company.cloud.files.stats.dto.TrafficPoint;
import com.company.cloud.files.stats.mapper.AdminStatsMapper;
import com.company.cloud.files.stats.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端大盘统计实现（R-C09）。
 *
 * <p>overview 服务端缓存 1 分钟（任务书 §6 建议）：全平台总览是 3 张管理页共用的头部数据，
 * 且为全表聚合，避免每个管理员每次刷新都打一遍 SUM；top-users / traffic 数据量小、参数各异，不缓存。
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private static final long OVERVIEW_TTL_MS = 60_000L;

    private final AdminStatsMapper mapper;

    /** 缓存槽：仅被单实例多线程读写，volatile 保证可见性；过期即重查，无需主动失效 */
    private volatile AdminStatsOverview cachedOverview;
    private volatile long cachedOverviewAt;

    @Override
    public AdminStatsOverview overview(Long entId) {
        // 缓存只服务"全平台"这一份：它是 3 张管理页共用的头部数据，且为全表聚合。
        // 企业维度不缓存 —— 每家企业各一份，缓存收益低且要维护多份 TTL，
        // 更重要的是**绝不能把全平台缓存错给企业**。
        if (entId != null) {
            return mapper.selectAdminOverview(entId);
        }
        AdminStatsOverview snapshot = cachedOverview;
        if (snapshot == null || System.currentTimeMillis() - cachedOverviewAt >= OVERVIEW_TTL_MS) {
            snapshot = mapper.selectAdminOverview(null);
            cachedOverview = snapshot;
            cachedOverviewAt = System.currentTimeMillis();
        }
        return snapshot;
    }

    @Override
    public List<TopUser> topUsers(Long entId) {
        return mapper.selectTopUsers(entId);
    }

    @Override
    public List<TrafficPoint> traffic(int days, Long entId) {
        int clamped = Math.min(Math.max(days, 1), 90);
        List<TrafficPoint> rows = mapper.selectTraffic(clamped, entId);

        // 无流量的日期补零点，保证曲线连续
        Map<LocalDate, TrafficPoint> byDay = new HashMap<>();
        for (TrafficPoint p : rows) {
            byDay.put(p.date(), p);
        }
        List<TrafficPoint> result = new ArrayList<>(clamped);
        LocalDate today = LocalDate.now();
        for (int i = clamped - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            result.add(byDay.getOrDefault(day, new TrafficPoint(day, 0L, 0L)));
        }
        return result;
    }
}
