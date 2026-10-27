package com.company.cloud.transfer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * files 表（B 组 Owner）——对齐 docs/files-table-spec.md 组间约定结构。
 *
 * 结构约定：
 *  - size（非 size_bytes）
 *  - parent_id NOT NULL DEFAULT 0（0 = 根目录）
 *  - sha256 TEXT（秒传哈希，目录为 null）
 *  - ref_count INT（引用计数，目录为 0）
 *  - 无 mime / storage_key 冗余字段（对象 key 由 sha256 推导 "objects/<sha256>"）
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 所属企业（M4）：0 = 个人域。JPA 侧必须与 DDL 严格一致（validate）。 */
    @Column(name = "enterprise_id", nullable = false)
    private Long enterpriseId = 0L;

    /** 父目录 id，0 表示根目录。 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_dir", nullable = false)
    private boolean isDir;

    /** 字节数（目录为 0）。 */
    @Column(nullable = false)
    private long size;

    /** 文件级哈希（秒传用），目录为 null。 */
    @Column(columnDefinition = "text")
    private String sha256;

    /** 引用计数（秒传去重用），目录为 0。 */
    @Column(name = "ref_count", nullable = false)
    private Integer refCount;

    /** 回收站软删标记（C 组维护），非空即已入回收站。 */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}