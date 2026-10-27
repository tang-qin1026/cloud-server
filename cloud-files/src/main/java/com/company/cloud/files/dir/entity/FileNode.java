package com.company.cloud.files.dir.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文件/目录节点（对应 files 表）。
 *
 * <p><b>注意：files 表 Owner 是 B 组</b>，本实体字段基于组间约定的表结构
 * （见 docs/files-table-spec.md），B 组正式 migration 交付后如有出入以 B 组为准、
 * 本类跟随调整；C 组只做行级读写，不改表结构。
 */
@Data
@TableName("files")
public class FileNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所有者用户 ID */
    private Long ownerId;

    /** 所属企业（M4）：0 = 个人域，与企业域隔离。 */
    private Long enterpriseId;

    /** 父目录 ID，0 表示根目录 */
    private Long parentId;

    /** 文件/目录名（同级唯一，重名自动追加 (2)(3) 后缀） */
    private String name;

    /** 是否目录 */
    private Boolean isDir;

    /** 字节数（目录为 0） */
    private Long size;

    /** 内容哈希（秒传用，B 组字段；目录为 null） */
    private String sha256;

    /** 引用计数（秒传用，B 组字段；目录为 0） */
    private Integer refCount;

    /** 软删时间（入回收站），null 表示正常 */
    private OffsetDateTime deletedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
