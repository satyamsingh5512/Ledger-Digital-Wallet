package com.walletsys.config;

import com.walletsys.security.jwt.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link RateLimitFilter} is a {@code @Component} so Spring wires its dependencies, and
 * is explicitly inserted into the Spring Security filter chain in {@link SecurityConfig}.
 * Without this registration bean, Spring Boot would ALSO auto-register it as a generic
 * servlet filter (since it implements {@code Filter}), causing every request to be
 * rate-limit-checked twice. Setting the registration to disabled prevents the duplicate
 * while leaving the bean itself intact for SecurityConfig to use.
 */
@Configuration
@RequiredArgsConstructor
public class FilterRegistrationConfig {

    private final RateLimitFilter rateLimitFilter;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableAutoRegistrationOfRateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}
