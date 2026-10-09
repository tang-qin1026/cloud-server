package com.company.cloud.portal.dto;

import com.company.cloud.portal.entity.PortalProvision;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /internal/provision 成功响应（契约 §3.2）：
 * <pre>{ "code": 0, "data": { "instance_id": "st-0001", "expire_at": "2026-11-07T09:02:02Z", "quantity": 1 } }</pre>
 */
@Schema(name = "ProvisionVO", description = "开通成功返回（HTTP 状态码恒为 200，成功时 code=0）")
public record ProvisionVO(

        @Schema(description = "实例号。**幂等**：同一个 order_item_id 重复调用返回相同的实例号",
                example = "st-000001")
        @JsonProperty("instance_id") String instanceId,

        @Schema(description = "到期时刻（UTC ISO-8601，截到秒）。到期后进入宽限期，宽限结束停机并回收配额",
                example = "2026-11-08T02:42:19Z")
        @JsonProperty("expire_at") String expireAt,

        @Schema(description = "本次开通的数量（回显入参，缺省为 1）", example = "1")
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
