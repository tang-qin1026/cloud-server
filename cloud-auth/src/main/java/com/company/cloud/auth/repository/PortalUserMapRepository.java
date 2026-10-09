package com.company.cloud.auth.repository;

import com.company.cloud.auth.entity.PortalUserMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * portal_user_map 数据访问（门户影子用户映射）。
 */
public interface PortalUserMapRepository extends JpaRepository<PortalUserMap, String> {

    /** 反查：本地用户是否已绑定门户用户（运维排查用）。 */
    Optional<PortalUserMap> findByLocalUserId(Long localUserId);
}
