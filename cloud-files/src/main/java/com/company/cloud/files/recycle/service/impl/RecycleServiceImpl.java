package com.company.cloud.files.recycle.service.impl;

import org.springframework.beans.factory.annotation.Value;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.cloud.common.audit.AuditActions;
import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.common.result.PageResult;
import com.company.cloud.files.dir.dto.FileNodeVO;
import com.company.cloud.files.dir.entity.FileNode;
import com.company.cloud.files.dir.mapper.FileNodeMapper;
import com.company.cloud.files.recycle.dto.RecycleItemVO;
import com.company.cloud.files.recycle.dto.SubtreeFile;
import com.company.cloud.files.recycle.ref.RefCountClient;
import com.company.cloud.files.recycle.service.RecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecycleServiceImpl implements RecycleService {

    private final FileNodeMapper mapper;
    private final RefCountClient refCountClient;
    private final AuditService auditService;

    /** 回收站保留期（天），与 R-C06 定时清理同一配置口径（recycle.retention-days）。 */
    @Value("${recycle.retention-days:30}")
    private int retentionDays;

    // ---------- R-C05 回收站列表 ----------

    @Override
    public PageResult<RecycleItemVO> list(Long userId, int page, int size, Long parent) {
        long parentId = parent == null ? 0L : parent;
        if (size <= 0) {
            List<RecycleItemVO> all = mapper.selectRecycleAll(userId, parentId).stream()
                    .map(n -> RecycleItemVO.from(n, retentionDays)).toList();
            return PageResult.of(all.size(), all);
        }
        Page<FileNode> result = mapper.selectRecyclePage(Page.of(page, size), userId, parentId);
        List<RecycleItemVO> list = result.getRecords().stream()
                .map(n -> RecycleItemVO.from(n, retentionDays)).toList();
        return PageResult.of(result.getTotal(), list);
    }

    // ---------- R-C06 还原 ----------

    @Override
    @Transactional
    public FileNodeVO restore(Long userId, Long id, Long targetParentId) {
        FileNode node = requireRecycleNode(userId, id);

        // 还原落点：未指定 targetParentId → 回原位（父不可用则落根）；指定 → 恢复到该目标目录
        long targetParent = targetParentId == null
                ? resolveRestoreParent(userId, node.getParentId())
                : resolveTargetParent(userId, targetParentId);
        // 目标位置重名自动改名（与 FileNodeServiceImpl 同款私有实现）
        String targetName = resolveUniqueName(userId, targetParent, node.getName());

        // 两步处理：先在回收站（deleted）状态下改顶层节点的 parent_id/name——
        // deleted 节点不在 uk_files_sibling_name 唯一索引范围内，改名不冲突；
        // 若先清 deleted_at 再用原名，中间态会与未删同名节点撞唯一约束（DuplicateKeyException）。
        node.setParentId(targetParent);
        node.setName(targetName);
        node.setDeletedAt(null); // 仅置空内存态（保证返回 VO 的 deletedAt 正确）；updateById 的 NOT_NULL 策略不写 deleted_at 列，真正清标记靠下面 restoreCascade
        mapper.updateById(node);
        // 再级联清整棵子树（含顶层自身）的 deleted_at；顶层已是改好名/落点状态，不会撞未删同名。
        mapper.restoreCascade(userId, id);

        auditService.record(new AuditEvent(
                userId, AuditActions.RESTORE, String.valueOf(id), null,
                Map.of("name", targetName, "size", node.getSize() == null ? 0L : node.getSize())));
        return FileNodeVO.from(node);
    }

    // ---------- R-C06 彻底删除 ----------

    @Override
    @Transactional
    public void forceDelete(Long userId, Long id) {
        // 彻底删除对「回收站里的项」和「还没删的活文件」都成立（前者来自回收站页，
        // 后者来自 DELETE /files/{id}?force=1）。只有"不是我的"才算不存在。
        FileNode node = mapper.selectById(id);
        if (node == null || !userId.equals(node.getOwnerId())) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        boolean wasInRecycle = node.getDeletedAt() != null;

        // 先收集子树文件清单（配额 + 引用计数用），再级联物理删除。
        // 两条 SQL 必须与"节点是否在回收站"匹配：回收站版 CTE 带 deleted_at IS NOT NULL，
        // 对活文件会返回 0 行 → 配额不释放、引用计数不减。
        List<SubtreeFile> files = wasInRecycle
                ? mapper.selectSubtreeFiles(userId, id)
                : mapper.selectSubtreeFilesAny(userId, id);
        long bytes = files.stream().mapToLong(f -> f.getSize() == null ? 0L : f.getSize()).sum();

        if (wasInRecycle) {
            mapper.deleteCascadePhysical(userId, id);
        } else {
            mapper.deleteCascadePhysicalAny(userId, id);
        }
        if (bytes > 0) {
            mapper.releaseQuota(userId, bytes);
        }
        // 每个内容哈希递减引用计数（R-C07；计数归 0 的物理清理由 B 组负责）
        files.stream()
                .filter(f -> f.getSha256() != null && !f.getSha256().isBlank())
                .forEach(f -> refCountClient.decrement(f.getSha256(), f.getSize() == null ? 0L : f.getSize()));

        auditService.record(new AuditEvent(
                userId, AuditActions.DELETE_FORCE, String.valueOf(id), null,
                Map.of("name", node.getName(), "size", bytes, "fromRecycle", wasInRecycle)));
    }

    // ---------- 内部工具 ----------

    /** 取本人回收站中的节点（deleted_at 非空），否则 40314 */
    private FileNode requireRecycleNode(Long userId, Long id) {
        FileNode node = mapper.selectById(id);
        if (node == null || !userId.equals(node.getOwnerId()) || node.getDeletedAt() == null) {
            throw new BizException(ErrorCode.RECYCLE_NOT_FOUND);
        }
        return node;
    }

    /** 原父目录可回（存在、未删、本人、是目录）则回原位，否则回根目录 0 */
    private long resolveRestoreParent(Long userId, Long parentId) {
        if (parentId == null || parentId == 0) {
            return 0;
        }
        FileNode parent = mapper.selectById(parentId);
        boolean usable = parent != null
                && userId.equals(parent.getOwnerId())
                && parent.getDeletedAt() == null
                && Boolean.TRUE.equals(parent.getIsDir());
        return usable ? parentId : 0;
    }

    /** 指定目标目录可回（0=根；或存在、未删、本人、是目录），否则 40304 */
    private long resolveTargetParent(Long userId, long targetParentId) {
        if (targetParentId == 0) {
            return 0;
        }
        FileNode target = mapper.selectById(targetParentId);
        boolean usable = target != null
                && userId.equals(target.getOwnerId())
                && target.getDeletedAt() == null
                && Boolean.TRUE.equals(target.getIsDir());
        if (!usable) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return targetParentId;
    }

    /**
     * 同级重名自动改名：base 已被占用则在扩展名前插入 (2)(3)... 序号
     * （与 FileNodeServiceImpl.resolveUniqueName 同款逻辑，其为 private 故在此复制一份）。
     */
    private String resolveUniqueName(Long userId, long parentId, String base) {
        List<String> siblings = mapper.selectSiblingNames(userId, parentId, base);
        if (!siblings.contains(base)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        int i = 2;
        while (siblings.contains(stem + " (" + i + ")" + ext)) {
            i++;
        }
        return stem + " (" + i + ")" + ext;
    }
}