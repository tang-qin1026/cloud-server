package com.company.cloud.files.recycle.service.impl;

import com.company.cloud.common.audit.AuditService;
import com.company.cloud.common.result.BizException;
import com.company.cloud.files.dir.entity.FileNode;
import com.company.cloud.files.dir.mapper.FileNodeMapper;
import com.company.cloud.files.recycle.ref.RefCountClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-C05 回收站恢复核心路径单元测试（任务书 §8 交付物：回收站恢复用例）。
 * 覆盖三态落点：原父可用回原位 / 原父不可用落根 / 原位重名自动改名；以及非回收站节点拒绝。
 */
@ExtendWith(MockitoExtension.class)
class RecycleServiceImplTest {

    private static final long UID = 9L;

    @Mock
    private FileNodeMapper mapper;

    @Mock
    private RefCountClient refCountClient;

    @Mock
    private AuditService auditService;

    private RecycleServiceImpl service;

    private FileNode recycleNode(Long id, Long parentId, String name) {
        FileNode n = new FileNode();
        n.setId(id);
        n.setOwnerId(UID);
        n.setParentId(parentId);
        n.setName(name);
        n.setIsDir(false);
        n.setSize(10L);
        n.setDeletedAt(OffsetDateTime.now().minusDays(3)); // 在回收站
        return n;
    }

    private FileNode dirNode(Long id, boolean deleted) {
        FileNode n = new FileNode();
        n.setId(id);
        n.setOwnerId(UID);
        n.setParentId(0L);
        n.setName("dir-" + id);
        n.setIsDir(true);
        n.setSize(0L);
        if (deleted) {
            n.setDeletedAt(OffsetDateTime.now().minusDays(1));
        }
        return n;
    }

    @BeforeEach
    void setUp() {
        service = new RecycleServiceImpl(mapper, refCountClient, auditService);
        ReflectionTestUtils.setField(service, "retentionDays", 30);
    }

    @Test
    void restoreBackToOriginalParentWhenParentUsable() {
        FileNode node = recycleNode(5L, 50L, "a.txt");
        when(mapper.selectById(5L)).thenReturn(node);
        when(mapper.selectById(50L)).thenReturn(dirNode(50L, false)); // 原父健在且是目录
        when(mapper.selectSiblingNames(eq(UID), eq(50L), eq("a.txt"))).thenReturn(List.of("b.txt"));

        service.restore(UID, 5L, null);

        ArgumentCaptor<FileNode> saved = ArgumentCaptor.forClass(FileNode.class);
        verify(mapper).restoreCascade(UID, 5L);          // 整棵子树先清 deleted_at
        verify(mapper).updateById(saved.capture());       // 再改顶层落点
        assertThat(saved.getValue().getParentId()).isEqualTo(50L);
        assertThat(saved.getValue().getName()).isEqualTo("a.txt"); // 无重名，原名保留
        assertThat(saved.getValue().getDeletedAt()).isNull();
        verify(auditService).record(any());
    }

    @Test
    void restoreFallsBackToRootWhenParentAlsoDeleted() {
        FileNode node = recycleNode(5L, 50L, "a.txt");
        when(mapper.selectById(5L)).thenReturn(node);
        when(mapper.selectById(50L)).thenReturn(dirNode(50L, true));  // 原父也在回收站 → 不可回
        when(mapper.selectSiblingNames(eq(UID), eq(0L), eq("a.txt"))).thenReturn(List.of());

        service.restore(UID, 5L, null);

        ArgumentCaptor<FileNode> saved = ArgumentCaptor.forClass(FileNode.class);
        verify(mapper).updateById(saved.capture());
        assertThat(saved.getValue().getParentId()).isEqualTo(0L); // 落根目录
        assertThat(saved.getValue().getName()).isEqualTo("a.txt");
    }

    @Test
    void restoreRenamesWhenTargetSlotOccupied() {
        FileNode node = recycleNode(5L, 0L, "a.txt");
        when(mapper.selectById(5L)).thenReturn(node);
        // parentId=0 → resolveRestoreParent 直接返回根，不查父节点
        when(mapper.selectSiblingNames(eq(UID), eq(0L), eq("a.txt")))
                .thenReturn(List.of("a.txt", "a (2).txt"));

        service.restore(UID, 5L, null);

        ArgumentCaptor<FileNode> saved = ArgumentCaptor.forClass(FileNode.class);
        verify(mapper).updateById(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("a (3).txt"); // 原名与 (2) 序号均被占
    }

    @Test
    void restoreRejectsNodeNotInRecycleBin() {
        FileNode alive = recycleNode(5L, 0L, "a.txt");
        alive.setDeletedAt(null); // 未删除，不属于回收站
        when(mapper.selectById(5L)).thenReturn(alive);

        // 40314 RECYCLE_NOT_FOUND
        assertThatThrownBy(() -> service.restore(UID, 5L, null)).isInstanceOf(BizException.class);
        verify(mapper, never()).updateById(any(FileNode.class));
        verify(auditService, never()).record(any());
    }

    @Test
    void forceDeleteReleasesQuotaAndRefCount() {
        FileNode node = recycleNode(5L, 0L, "a.txt");
        when(mapper.selectById(5L)).thenReturn(node);
        // selectSubtreeFiles 返回两个文件（其中一个秒传共享）
        doAnswer(inv -> {
            com.company.cloud.files.recycle.dto.SubtreeFile f1 = new com.company.cloud.files.recycle.dto.SubtreeFile();
            f1.setSize(100L);
            f1.setSha256("hash-a");
            com.company.cloud.files.recycle.dto.SubtreeFile f2 = new com.company.cloud.files.recycle.dto.SubtreeFile();
            f2.setSize(50L);
            f2.setSha256(null); // 无哈希不参与引用计数
            return List.of(f1, f2);
        }).when(mapper).selectSubtreeFiles(UID, 5L);

        service.forceDelete(UID, 5L);

        verify(mapper).deleteCascadePhysical(UID, 5L);
        verify(mapper).releaseQuota(UID, 150L);       // 100 + 50
        verify(refCountClient).decrement(eq("hash-a"), eq(100L)); // 仅共享文件递减
        verify(auditService).record(any());
    }

    @Test
    void forceDeleteRejectsForeignNode() {
        when(mapper.selectById(5L)).thenReturn(null);

        assertThatThrownBy(() -> service.forceDelete(UID, 5L)).isInstanceOf(BizException.class);
        verify(mapper, never()).deleteCascadePhysical(anyLong(), anyLong());
        verify(refCountClient, never()).decrement(any(), anyLong());
    }
}
