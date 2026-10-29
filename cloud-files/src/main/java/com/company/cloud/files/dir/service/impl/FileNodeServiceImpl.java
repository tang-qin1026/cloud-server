package com.company.cloud.files.dir.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.cloud.common.audit.AuditActions;
import com.company.cloud.common.audit.AuditEvent;
import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.common.result.ErrorCode;
import com.company.cloud.files.dir.dto.BatchMoveRequest;
import com.company.cloud.files.dir.dto.DirListResponse;
import com.company.cloud.files.dir.dto.FileNodeVO;
import com.company.cloud.files.dir.dto.MkdirRequest;
import com.company.cloud.files.dir.dto.UpdateNodeRequest;
import com.company.cloud.files.dir.entity.FileNode;
import com.company.cloud.files.dir.mapper.FileNodeMapper;
import com.company.cloud.files.dir.service.FileNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.company.cloud.auth.org.OrgService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileNodeServiceImpl implements FileNodeService {

    /** M3 数据范围：把 owner_id = me 放宽成范围内可见的人。 */
    private final OrgService orgService;
    /** 权限点校验（删自己的 → file.delete；删他人 → file.other.delete）。 */
    private final com.company.cloud.auth.rbac.PermissionChecker permissionChecker;

    /** 排序字段白名单（契约：禁止前端字段直接透传 SQL） */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "size", "size",
            "createdAt", "created_at",
            "updatedAt", "updated_at"
    );

    private final FileNodeMapper mapper;
    private final AuditService auditService;

    // ---------- R-C01 列目录（+ keyword 全局搜索） ----------

    @Override
    public DirListResponse list(Long userId, long parentId, int page, int size, String sort, String keyword) {
        boolean searching = keyword != null && !keyword.isBlank();
        if (!searching && parentId > 0) {
            requireOwnedDir(userId, parentId);
        }

        QueryWrapper<FileNode> qw = new QueryWrapper<>();
        // 数据范围（M3）：把 owner_id = me 放宽成 owner_id IN (范围内的人)。
        // 范围只决定"看得到谁的数据"；能不能读他人文件仍要 file.other.read 功能权限（另一个闸门）。
        java.util.List<Long> owners = resolveVisibleOwnerIds(userId);
        if (owners.size() == 1) {
            qw.eq("owner_id", owners.get(0));
        } else {
            qw.in("owner_id", owners);
        }
        // 租户条件（M4）：与 owner 条件并存，是**纵深防御**而非替代。
        // 三个索引都以 enterprise_id 打头，因此这个条件同时是索引前缀，不会变慢。
        // 平台管理员返回 null，不加该条件（需要跨企业视角）。
        Long entFilter = resolveEnterpriseFilter();
        if (entFilter != null) {
            qw.eq("enterprise_id", entFilter);
        }
        qw.isNull("deleted_at");
        if (searching) {
            // 全局搜索：忽略 parent，全空间按名称模糊匹配；%/_ 转义防通配符注入
            qw.like("name", escapeLike(keyword.trim()));
        } else {
            qw.eq("parent_id", parentId);
        }
        applySort(qw, sort);

        Page<FileNode> result = mapper.selectPage(Page.of(page, size), qw);
        List<FileNodeVO> list = result.getRecords().stream().map(FileNodeVO::from).toList();

        return new DirListResponse(
                result.getTotal(),
                list,
                (!searching && parentId > 0) ? mapper.selectBreadcrumb(userId, parentId) : List.of()
        );
    }

    /** PostgreSQL LIKE 默认以反斜杠为转义符，逐字符转义即可 */
    private static String escapeLike(String kw) {
        return kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------- 目录树（前端树状导航 / 拖拽移动） ----------

    @Override
    public List<FileNodeVO> tree(Long userId) {
        QueryWrapper<FileNode> qw = new QueryWrapper<>();
        qw.eq("owner_id", userId);
        // 租户条件（M4）：目录树只取本人的目录，但显式带上企业条件做纵深防御，
        // 且 enterprise_id 是索引前缀，命中更精确。
        Long entFilter = resolveEnterpriseFilter();
        if (entFilter != null) {
            qw.eq("enterprise_id", entFilter);
        }
        qw.eq("is_dir", true)
          .isNull("deleted_at")
          .orderByAsc("name");
        return mapper.selectList(qw).stream().map(FileNodeVO::from).toList();
    }

    private void applySort(QueryWrapper<FileNode> qw, String sort) {
        if (sort == null || sort.isBlank()) {
            // 默认：目录在前，名称升序
            qw.orderByDesc("is_dir").orderByAsc("name");
            return;
        }
        String[] parts = sort.split(",");
        String column = SORT_COLUMNS.get(parts[0].trim());
        if (column == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的排序字段: " + parts[0]);
        }
        boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1].trim());
        qw.orderBy(true, asc, column);
    }

    // ---------- R-C02 新建文件夹 ----------

    @Override
    public FileNodeVO mkdir(Long userId, MkdirRequest request) {
        long parentId = request.parentIdOrRoot();
        if (parentId > 0) {
            requireOwnedDir(userId, parentId);
        }

        FileNode node = new FileNode();
        node.setOwnerId(userId);
        node.setEnterpriseId(currentEnterpriseId());
        node.setParentId(parentId);
        node.setName(resolveUniqueName(userId, parentId, request.name().trim()));
        node.setIsDir(true);
        node.setSize(0L);
        node.setRefCount(0);
        mapper.insert(node);
        // 审计：ip 暂传 null（TODO：A 组 Security 交付后从请求上下文补齐）
        auditService.record(new AuditEvent(
                userId, AuditActions.MKDIR, String.valueOf(node.getId()), null,
                Map.of("name", node.getName(), "id", node.getId(), "parentId", parentId)));
        return FileNodeVO.from(node);
    }

    // ---------- R-C03 重命名 / 移动 ----------

    @Override
    @Transactional
    public FileNodeVO update(Long userId, Long id, UpdateNodeRequest request) {
        if (request.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 与 parentId 至少传一个");
        }
        FileNode node = requireOwnedNode(userId, id);

        String newName = (request.name() == null || request.name().isBlank())
                ? node.getName()
                : request.name().trim();
        Long targetParent = request.parentId();

        boolean isMove = targetParent != null && !targetParent.equals(node.getParentId());
        long fromParentId = node.getParentId() == null ? 0L : node.getParentId();
        if (isMove) {
            if (targetParent > 0) {
                requireOwnedDir(userId, targetParent);
                // R-C03 红线：禁止移入自身或其子目录（递归 CTE 一条 SQL 判定）
                if (mapper.countInSubtree(id, targetParent) > 0) {
                    throw new BizException(ErrorCode.MOVE_INTO_SUBDIR);
                }
            }
            node.setParentId(targetParent);
            node.setName(resolveUniqueName(userId, targetParent, newName));
        } else if (!newName.equals(node.getName())) {
            node.setName(resolveUniqueName(userId, node.getParentId(), newName));
        }

        mapper.updateById(node);
        if (isMove) {
            // 审计：移动（含前端拖拽），记录源/目标目录
            auditService.record(new AuditEvent(
                    userId, AuditActions.MOVE, String.valueOf(id), null,
                    Map.of("name", node.getName(), "id", id,
                            "fromParentId", fromParentId,
                            "toParentId", targetParent)));
        }
        return FileNodeVO.from(node);
    }

    // ---------- 批量移动（前端多选拖拽） ----------

    @Override
    @Transactional
    public List<FileNodeVO> batchMove(Long userId, BatchMoveRequest request) {
        long targetParent = request.targetParentId();
        if (targetParent > 0) {
            requireOwnedDir(userId, targetParent);
        }

        List<FileNodeVO> moved = new ArrayList<>();
        for (Long id : request.ids()) {
            FileNode node = requireOwnedNode(userId, id);
            long currentParent = node.getParentId() == null ? 0L : node.getParentId();
            if (currentParent == targetParent) {
                // 已在目标目录，跳过不记审计
                moved.add(FileNodeVO.from(node));
                continue;
            }
            if (targetParent > 0 && mapper.countInSubtree(id, targetParent) > 0) {
                throw new BizException(ErrorCode.MOVE_INTO_SUBDIR);
            }
            node.setParentId(targetParent);
            node.setName(resolveUniqueName(userId, targetParent, node.getName()));
            mapper.updateById(node);

            auditService.record(new AuditEvent(
                    userId, AuditActions.MOVE, String.valueOf(id), null,
                    Map.of("name", node.getName(), "id", id,
                            "fromParentId", currentParent,
                            "toParentId", targetParent)));
            moved.add(FileNodeVO.from(node));
        }
        return moved;
    }

    // ---------- R-C04 删除入回收站（级联软删） ----------

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        // 幂等：不存在或已删除 → 直接视为成功（契约要求，不报错）
        FileNode node = mapper.selectById(id);
        if (node == null || node.getDeletedAt() != null) {
            return;
        }

        // 归属与范围判定（两个维度，缺一不可）：
        //   自己的文件        → 需要 file.delete
        //   他人但在数据范围内 → 需要 file.other.delete
        //   不在数据范围内     → 按不存在处理（404），不通过删除接口泄露存在性
        boolean own = userId.equals(node.getOwnerId());
        if (!own && !resolveVisibleOwnerIds(userId).contains(node.getOwnerId())) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        requirePermForDelete(own);

        // 软删 CTE 的锚点是 (id, owner_id)：删他人时必须传**文件的真实 owner**，
        // 传调用者会命中 0 行（表现为"删了但没删掉"）。
        mapper.softDeleteCascade(node.getOwnerId(), id);
    }

    /**
     * 删除权限点校验。
     *
     * <p>{@code file.delete} 与 {@code file.other.delete} 是**两个独立勾选项**：
     * 只勾前者 → 只能删自己的；只勾后者 → 只能删他人的；
     * 都勾 → 两者都能删；都不勾 → 一律不能删。
     *
     * <p>拿不到 SecurityContext 时（单测、系统内部调用）跳过校验，保持既有行为。
     */
    private void requirePermForDelete(boolean own) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null
                    && auth.getPrincipal() instanceof com.company.cloud.auth.security.CurrentUser cu) {
                permissionChecker.require(cu, own ? "file.delete" : "file.other.delete");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[perm] 删除权限校验异常，按拒绝处理 err={}", e.toString());
            throw new BizException(ErrorCode.FORBIDDEN, "缺少权限：file.delete / file.other.delete");
        }
    }

    // ---------- 内部工具 ----------

    /** 取本人名下未删除节点，否则 404 */
    private FileNode requireOwnedNode(Long userId, Long id) {
        FileNode node = mapper.selectById(id);
        if (node == null || !userId.equals(node.getOwnerId()) || node.getDeletedAt() != null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return node;
    }

    /** 取本人名下未删除目录，否则 404 */
    private FileNode requireOwnedDir(Long userId, Long id) {
        FileNode node = requireOwnedNode(userId, id);
        if (!Boolean.TRUE.equals(node.getIsDir())) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "目标不是目录");
        }
        return node;
    }

    /**
     * 同级重名自动改名：base 已被占用则在扩展名前插入 (2)(3)... 序号
     * （如 test.docx → test (2).docx；无扩展名/目录则尾部追加）。
     *
     * <p>TODO（并发兜底）：需 B 组在 files 表加部分唯一索引，防并发重名——
     * {@code CREATE UNIQUE INDEX ... ON files(owner_id, parent_id, name) WHERE deleted_at IS NULL}
     * 已登记在 docs/files-table-spec.md，待 B 组 migration 落实。
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
    /**
     * 解析数据范围内可见的 owner id（含自己）。
     *
     * <p>从 SecurityContext 取 {@code CurrentUser} 而不是改方法签名——这样
     * 所有既有调用点（含定时任务、内部调用）都不用动，改动面最小。
     * 拿不到身份时**fail-closed**：只返回自己。
     */
    private java.util.List<Long> resolveVisibleOwnerIds(Long userId) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null
                    && auth.getPrincipal() instanceof com.company.cloud.auth.security.CurrentUser cu) {
                return orgService.visibleOwnerIds(cu);
            }
        } catch (Exception e) {
            log.warn("[scope] 解析数据范围失败，回退为仅自己 userId={} err={}", userId, e.toString());
        }
        return java.util.List.of(userId);
    }
    /**
     * 当前租户 id（写入 {@code files.enterprise_id} 用）。
     *
     * <p>没有租户上下文时返回 0（个人域）——与列默认值一致，
     * 也保证定时任务等系统路径不会写出脏值。
     */
    private static long currentEnterpriseId() {
        try {
            return com.company.cloud.common.tenant.TenantContext.isPresent()
                    ? com.company.cloud.common.tenant.TenantContext.require()
                    : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    /**
     * 租户过滤值：{@code null} = 不加企业条件（平台管理员 / 无上下文）。
     *
     * <p>用于 MyBatis-Plus 的 QueryWrapper 读查询。**注意与 {@link #currentEnterpriseId()} 的区别**：
     * 那个用于**写入**（必须有个具体值，默认 0），这个用于**读取**（null 表示不限制）。
     */
    private static Long resolveEnterpriseFilter() {
        try {
            if (com.company.cloud.common.tenant.TenantContext.isBypass()
                    || !com.company.cloud.common.tenant.TenantContext.isPresent()) {
                return null;
            }
            return com.company.cloud.common.tenant.TenantContext.require();
        } catch (Exception e) {
            return null;
        }
    }
}