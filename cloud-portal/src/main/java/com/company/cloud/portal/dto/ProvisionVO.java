package com.company.cloud.portal.dto;

import com.company.cloud.portal.entity.PortalProvision;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /internal/provision 成功响应（契约 §3.2）：
 * <pre>{ "code": 0, "data": { "instance_id": "st-0001", "expire_at": "2026-11-07T09:02:02Z", "quantity": 1 } }</pre>
 */
public record ProvisionVO(
        @JsonProperty("instance_id") String instanceId,
        @JsonProperty("expire_at") String expireAt,
        @JsonProperty("quantity") Integer quantity) {

    public static ProvisionVO of(PortalProvision p) {
        return new ProvisionVO(
                p.getInstanceId(),
                p.getExpireAt() == null
                        ? null
                        // 契约示例为 UTC 的 ISO-8601（...Z），截到秒
                        : p.getExpireAt().toInstant()
                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                .toString(),
                p.getQuantity());
    }
}
