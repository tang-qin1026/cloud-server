package com.company.cloud.transfer.repository;

import com.company.cloud.transfer.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FileEntityRepository extends JpaRepository<FileEntity, Long> {

    /** 秒传命中：同 sha256 的存活文件记录（内容寻址去重）。 */
        /**
     * 秒传判定：**必须按企业隔离**。
     *
     * <p>原方法 {@code findFirstBySha256AndDeletedAtIsNull} 只按哈希查，
     * 会把别家企业上传过的内容当成"本企业已存在"→ 跨企业复用同一对象，
     * 且 ref_count 共享（一方彻底删除可能影响另一方）。按《实现文档 v3》§6.2-4 修正。
     */
    Optional<FileEntity> findFirstByEnterpriseIdAndSha256AndDeletedAtIsNull(Long enterpriseId, String sha256);

    /** 指定 sha256 的存活文件数（彻底删除时判断是否还有引用，归 0 才物理删 MinIO 对象）。 */
    long countBySha256AndDeletedAtIsNull(String sha256);

    /** 同级同名（存活）是否存在——上传落库前自动重命名用（对齐 uk_files_sibling_name 的 WHERE deleted_at IS NULL）。 */
    boolean existsByOwnerIdAndParentIdAndNameAndDeletedAtIsNull(Long ownerId, Long parentId, String name);
}