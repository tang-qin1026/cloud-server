package com.company.cloud.files.dir.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.cloud.files.dir.dto.BreadcrumbItem;
import com.company.cloud.files.dir.entity.FileNode;
import com.company.cloud.files.recycle.dto.SubtreeFile;
import com.company.cloud.files.stats.dto.StatsOverview;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * files 表数据访问。树形操作用 PostgreSQL 递归 CTE（见 FileNodeMapper.xml），
 * 禁止代码内递归查库（单目录 5000 文件 P95 <500ms 的验收全靠它）。
 */
@Mapper
public interface FileNodeMapper extends BaseMapper<FileNode> {

    /**
     * 面包屑：自当前节点向上递归到根，按从根到当前排序。
     */
    List<BreadcrumbItem> selectBreadcrumb(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 级联软删：自身 + 全部子孙打 deleted_at 标记，返回影响行数。
     */
    int softDeleteCascade(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * targetId 是否在 id 的子树中（含 id 自身）。移动校验用：
     * 返回值 > 0 表示目标是自身或子孙，必须拒绝（R-C03）。
     */
    int countInSubtree(@Param("id") Long id, @Param("targetId") Long targetId);

    /**
     * 同级全部未删除文件名（重名自动改名时由调用方按需 contains 判断）。
     * base 参数保留仅为接口语义（原用于前缀过滤，现统一返回全量）。
     */
    @Select("""
            SELECT name FROM files
            WHERE owner_id = #{userId}
              AND parent_id = #{parentId}
              AND deleted_at IS NULL
            """)
    List<String> selectSiblingNames(@Param("userId") Long userId,
                                    @Param("parentId") Long parentId,
                                    @Param("base") String base);

    // ---------- 回收站（R-C05/C06，SQL 见 FileNodeMapper.xml） ----------

    /**
     * 回收站分页：本人全部已删除节点（含子孙，parentId 保留删除前原值，供前端重建目录树），
     * 按 deleted_at DESC。MP Page 首参自动分页。
     */
    Page<FileNode> selectRecyclePage(Page<FileNode> page, @Param("userId") Long userId, @Param("parentId") Long parentId);

    /**
     * 回收站全量：本人全部已删除节点（不分页，含子孙），供前端一次拉取重建目录树。
     */
    List<FileNode> selectRecycleAll(@Param("userId") Long userId, @Param("parentId") Long parentId);

    /**
     * 级联还原：自身 + 全部已删子孙清 deleted_at（不碰 parent_id / name，
     * 顶层节点的 parent/name 由调用方单独 UPDATE）。
     */
    int restoreCascade(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 子树文件清单：回收站子树内全部 is_dir=false 节点的 id/sha256/size
     * （彻底删除前收集，用于释放配额与递减引用计数）。
     */
    List<SubtreeFile> selectSubtreeFiles(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 级联物理删除：自身 + 全部已删子孙 DELETE（回收站彻底删除）。
     */
    int deleteCascadePhysical(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 子树文件清单（**不区分是否在回收站**）。
     *
     * <p>用于 {@code DELETE /files/{id}?force=1} 对**未删除的活文件**做彻底删除：
     * {@link #selectSubtreeFiles} 的 CTE 硬编码了 {@code deleted_at IS NOT NULL}，
     * 对活文件会返回 0 行 → 配额不释放、引用计数不减。所以单独开一条。
     */
    List<SubtreeFile> selectSubtreeFilesAny(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 级联物理删除（**不区分是否在回收站**）：活文件及其全部子孙直接 DELETE。
     */
    int deleteCascadePhysicalAny(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 释放用户配额（users 表 Owner 是 A 组，但释放配额是 C 组彻底删除的职责，
     * 直接 SQL；GREATEST 兜底防负值）。
     */
    int releaseQuota(@Param("userId") Long userId, @Param("bytes") long bytes);

    // ---------- 引用计数（R-C07，B 组接口未交付，本地实现） ----------

    /**
     * 递减指定 sha256 存活文件的引用计数（GREATEST 兜底防负值）。
     */
    int decrementRefCount(@Param("sha256") String sha256);

    /**
     * 指定 sha256 存活记录的最小 ref_count；无存活记录返回 null。
     */
    @Select("""
            SELECT MIN(ref_count) FROM files
            WHERE sha256 = #{sha256} AND is_dir = false AND deleted_at IS NULL
            """)
    Integer selectMinAliveRefCount(@Param("sha256") String sha256);

    // ---------- 统计大盘（R-C10，@Select 注解聚合） ----------

    /** 存活文件总数 */
    @Select("SELECT COUNT(*) FROM files WHERE owner_id = #{userId} AND is_dir = false AND deleted_at IS NULL")
    long countActiveFiles(@Param("userId") Long userId);

    /** 存活文件总字节 */
    @Select("SELECT COALESCE(SUM(size), 0) FROM files WHERE owner_id = #{userId} AND is_dir = false AND deleted_at IS NULL")
    long sumActiveBytes(@Param("userId") Long userId);

    /** 存活目录总数 */
    @Select("SELECT COUNT(*) FROM files WHERE owner_id = #{userId} AND is_dir = true AND deleted_at IS NULL")
    long countActiveDirs(@Param("userId") Long userId);

    /** 今日新增（文件 + 目录） */
    @Select("SELECT COUNT(*) FROM files WHERE owner_id = #{userId} AND created_at >= CURRENT_DATE")
    long countTodayNew(@Param("userId") Long userId);

    /** 回收站条数 */
    @Select("SELECT COUNT(*) FROM files WHERE owner_id = #{userId} AND deleted_at IS NOT NULL")
    long countRecycle(@Param("userId") Long userId);

    /** 回收站占用字节 */
    @Select("SELECT COALESCE(SUM(size), 0) FROM files WHERE owner_id = #{userId} AND deleted_at IS NOT NULL")
    long sumRecycleBytes(@Param("userId") Long userId);

    /** 扩展名分布 TOP10（按文件数降序） */
    @Select("""
            SELECT lower(substring(name from '\\.([^.]+)$')) AS ext,
                   COUNT(*) AS cnt,
                   COALESCE(SUM(size), 0) AS bytes
            FROM files
            WHERE owner_id = #{userId}
              AND is_dir = false
              AND deleted_at IS NULL
              AND name LIKE '%.%'
            GROUP BY ext
            ORDER BY cnt DESC
            LIMIT 10
            """)
    @ConstructorArgs({
            @Arg(column = "ext", javaType = String.class),
            @Arg(column = "cnt", javaType = long.class),
            @Arg(column = "bytes", javaType = long.class)
    })
    List<StatsOverview.TypeCount> selectTypeBreakdown(@Param("userId") Long userId);

    // ---------- 回收站超期清理（R-C06，SQL 见 FileNodeMapper.xml） ----------

    /**
     * 回收站超期待清理<b>顶层节点</b>（跨全部用户，系统任务）：
     * 已删除且 deleted_at 早于 cutoff（now - retentionDays），且父节点不在回收站中
     * （或父为根）——与回收站列表 selectRecycleTopPage 同款顶层语义，
     * 子树随顶层祖先整棵过期。按 deleted_at 升序（最老的先清），LIMIT 限流。
     */
    List<FileNode> selectPurgeCandidates(@Param("cutoff") java.time.OffsetDateTime cutoff,
                                         @Param("limit") int limit);
}
