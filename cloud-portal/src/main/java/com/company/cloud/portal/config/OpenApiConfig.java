package com.company.cloud.portal.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 全局配置。
 *
 * <p>只做两件事，目的是让 Swagger UI 能直接用来手工测试门户接入：
 * <ol>
 *   <li>声明文档基本信息；</li>
 *   <li>注册两个安全方案，Swagger UI 右上角因此会出现 <b>Authorize</b> 按钮：
 *       <ul>
 *         <li>{@code internalKey} —— 请求头 {@code X-Internal-Key}，用于 {@code /internal/**}</li>
 *         <li>{@code bearerAuth} —— {@code Authorization: Bearer <JWT>}，用于管理端接口</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>两点刻意的取舍：
 * <ul>
 *   <li><b>不设置全局 security</b>：全局 security 会把锁图标挂到所有接口（含 /auth/login）上造成误导，
 *       改为在需要鉴权的接口上逐个标注 {@code @SecurityRequirement}。</li>
 *   <li><b>不在这里声明 tags</b>：标签由各控制器上的 {@code @Tag(name=..., description=...)} 声明。
 *       两处都声明会让 SpringDoc 生成重复的 tag 条目（实测过），因此只保留控制器一处。</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名：门户内部调用密钥（请求头 X-Internal-Key）。 */
    public static final String SEC_INTERNAL_KEY = "internalKey";

    /** 安全方案名：Bearer JWT（本系统 HS256 或门户 RS256 均可）。 */
    public static final String SEC_BEARER = "bearerAuth";

    @Bean
    public OpenAPI cloudServerOpenAPI() {
        String description = """
                ## 统一门户接入（云存储侧）

                本产品接入《云平台统一门户》所需的接口，分为两组：

                | 分组 | 路径 | 鉴权 | 调用方 |
                |---|---|---|---|
                | 门户内网接口 | `/api/internal/**` | 请求头 `X-Internal-Key` | 门户主动调用 |
                | 门户运维接口 | `/api/admin/portal/**` | Bearer JWT + admin 角色 | 运维/联调手工触发 |

                ### 测试前必做：点右上角 Authorize
                1. **internalKey**：填平台侧下发的密钥（对应 `app.portal.internal-key`）。
                   未配置时后端会 fail-closed，一律返回 `HTTP 401 {"code":40001}`。
                2. **bearerAuth**：填 `admin / Admin@123` 调 `/api/auth/login` 拿到的 `accessToken`
                   （**只填 token 本身，不要带 "Bearer " 前缀**；注意 accessToken 在响应 `data.accessToken`，不是 `data.token`）。

                ### 两个必须知道的约定
                - **`price_cents` / `amount_cents` 单位一律是「分」**，不是元。
                - **`/internal/provision` 的 HTTP 状态码恒为 200**，成败看 body 里的 `code`
                  （`0` 成功 / `4003` sku 不存在 / `4004` 库存不足 / `5001` 内部错误）。POST 默认的 201 会让门户判错。
                """;

        return new OpenAPI()
                .info(new Info()
                        .title("cloud-server · 云存储平台 API")
                        .version("v1")
                        .description(description))
                .components(new Components()
                        .addSecuritySchemes(SEC_INTERNAL_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Key")
                                .description("门户 → 本产品的内部调用密钥（后端配置项 app.portal.internal-key）。"
                                        + "仅作用于 /internal/**，未配置该密钥时后端拒绝一切内部调用（fail-closed）。"))
                        .addSecuritySchemes(SEC_BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("两种令牌都被接受："
                                        + "① 本系统自签 HS256（POST /api/auth/login 获取，管理端接口用这个）；"
                                        + "② 门户签发的 RS256（业务路由用，后端按 JWKS 本地验签）。")));
    }
}
