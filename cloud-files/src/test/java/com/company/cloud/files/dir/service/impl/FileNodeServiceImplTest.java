package com.company.cloud.files.dir.service.impl;

import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.files.dir.dto.MkdirRequest;
import com.company.cloud.files.dir.dto.UpdateNodeRequest;
import com.company.cloud.files.dir.entity.FileNode;
import com.company.cloud.files.dir.mapper.FileNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-C02 / R-C03 核心路径单元测试（任务书 §8 交付物：目录树边界用例）。
 * 覆盖：新建同级重名自动改名、移动防环（禁止移入自身子目录）、重命名目标位冲突改名。
 */
@ExtendWith(MockitoExtension.class)
class FileNodeServiceImplTest {

    private static final long UID = 9L;

    @Mock
    private FileNodeMapper mapper;

    @Mock
    private AuditService auditService;

    private FileNodeServiceImpl service;

    private FileNode dirNode(Long id, Long parentId, String name) {
        FileNode n = new FileNode();
        n.setId(id);
        n.setOwnerId(UID);
        n.setParentId(parentId);
        n.setName(name);
        n.setIsDir(true);
        n.setSize(0L);
        return n;
    }

    @BeforeEach
    void setUp() {
        service = new FileNodeServiceImpl(
                org.mockito.Mockito.mock(com.company.cloud.auth.org.OrgService.class),
                org.mockito.Mockito.mock(com.company.cloud.auth.rbac.PermissionChecker.class),
                mapper, auditService);
    }

    // ---------- R-C02 新建文件夹 ----------

    @Test
    void mkdirKeepsNameWhenNoSiblingConflict() {
        when(mapper.selectSiblingNames(UID, 0L, "docs")).thenReturn(List.of("other"));
        doAnswer(inv -> {
            inv.getArgument(0, FileNode.class).setId(100L); // insert 回填主键
            return 1;
        }).when(mapper).insert(any(FileNode.class));

        var vo = service.mkdir(UID, new MkdirRequest(null, "docs"));

        ArgumentCaptor<FileNode> inserted = ArgumentCaptor.forClass(FileNode.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getName()).isEqualTo("docs");
        assertThat(vo.name()).isEqualTo("docs");
        verify(auditService).record(any());
    }

    @Test
    void mkdirAppendsNextFreeSuffixOnConflict() {
        // "docs" 与 "docs (2)" 均被占用 → 应落 "docs (3)"
        when(mapper.selectSiblingNames(UID, 0L, "docs")).thenReturn(List.of("docs", "docs (2)"));
        doAnswer(inv -> {
            inv.getArgument(0, FileNode.class).setId(101L);
            return 1;
        }).when(mapper).insert(any(FileNode.class));

        var vo = service.mkdir(UID, new MkdirRequest(null, "docs"));

        assertThat(vo.name()).isEqualTo("docs (3)");
    }

    // ---------- R-C03 重命名 / 移动 ----------

    @Test
    void updateRejectsMovingDirectoryIntoItsOwnSubtree() {
        FileNode node = dirNode(5L, 1L, "d");
        when(mapper.selectById(5L)).thenReturn(node);
        when(mapper.selectById(99L)).thenReturn(dirNode(99L, 5L, "sub")); // 目标存在且可用
        when(mapper.countInSubtree(5L, 99L)).thenReturn(1);               // 但在自身子树内 → 拒绝

        // 40306 MOVE_INTO_SUBDIR
        assertThatThrownBy(() -> service.update(UID, 5L, new UpdateNodeRequest(null, 99L)))
                .isInstanceOf(BizException.class);
        verify(mapper, never()).updateById(any(FileNode.class));
        verify(auditService, never()).record(any());
    }

    @Test
    void updateRenamesWithSuffixWhenTargetSlotOccupied() {
        FileNode node = dirNode(5L, 1L, "old-name");
        when(mapper.selectById(5L)).thenReturn(node);
        when(mapper.selectSiblingNames(UID, 1L, "b")).thenReturn(List.of("b"));

        var vo = service.update(UID, 5L, new UpdateNodeRequest("b", null));

        ArgumentCaptor<FileNode> saved = ArgumentCaptor.forClass(FileNode.class);
        verify(mapper).updateById(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("b (2)");
        assertThat(saved.getValue().getParentId()).isEqualTo(1L); // 只改名不移动
        assertThat(vo.name()).isEqualTo("b (2)");
        // 纯重命名不落 move 审计
        verify(auditService, never()).record(any());
    }

    @Test
    void updateRejectsEmptyRequest() {
        assertThatThrownBy(() -> service.update(UID, 5L, new UpdateNodeRequest(null, null)))
                .isInstanceOf(BizException.class);
        verify(mapper, never()).updateById(any(FileNode.class));
    }
}
