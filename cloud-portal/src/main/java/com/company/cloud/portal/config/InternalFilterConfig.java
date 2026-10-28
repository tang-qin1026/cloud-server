package com.company.cloud.portal.config;

import com.company.cloud.portal.filter.InternalServiceAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 把 {@link InternalKeyFilter} 注册为仅作用于 {@code /internal/*} 的 Servlet Filter。
 *
 * <p>注册方式说明：SecurityConfig 在 cloud-auth 中，cloud-portal 依赖它，
 * 反向依赖会造成模块循环。因此内网接口的密钥校验走独立的 FilterRegistrationBean，
 * 以最高优先级在 Spring Security 过滤链之前执行并短路；SecurityConfig 只需对
 * {@code /internal/**} 放行（permitAll）即可。
 *
 * <p>注意：URL pattern 相对 context-path（本应用为 {@code /api}），
 * 所以 {@code /internal/*} 对应的真实路径是 {@code /api/internal/*}。
 */
@Configuration
public class InternalFilterConfig {

    @Bean
    public FilterRegistrationBean<InternalServiceAuthFilter> internalAuthFilterRegistration(
            PortalProperties props,
            com.company.cloud.auth.portal.ServiceAuthProperties svcProps,
            com.company.cloud.auth.portal.ServiceCredentialVerifier verifier,
            com.company.cloud.auth.portal.PortalJwksClient jwksClient,
            org.springframework.data.redis.core.StringRedisTemplate redis) {
        // 《27》改造：优先服务凭据（RS256），未配置时该 Filter 内部自动回退 X-Internal-Key。
        // 因此这里直接取代旧的 InternalKeyFilter 注册，而不是两个一起挂——
        // 两个一起挂会导致 disabled 时旧 Filter 仍然生效、enabled 时旧 Filter 误拒。
        FilterRegistrationBean<InternalServiceAuthFilter> bean =
                new FilterRegistrationBean<>(new InternalServiceAuthFilter(
                        props, svcProps, verifier, jwksClient, redis));
        bean.addUrlPatterns("/internal/*");
        bean.setName("portalInternalKeyFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
