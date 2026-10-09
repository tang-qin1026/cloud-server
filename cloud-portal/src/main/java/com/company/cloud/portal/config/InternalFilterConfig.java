package com.company.cloud.portal.config;

import com.company.cloud.portal.filter.InternalKeyFilter;
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
    public FilterRegistrationBean<InternalKeyFilter> internalKeyFilterRegistration(PortalProperties props) {
        FilterRegistrationBean<InternalKeyFilter> bean =
                new FilterRegistrationBean<>(new InternalKeyFilter(props));
        bean.addUrlPatterns("/internal/*");
        bean.setName("portalInternalKeyFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
