package com.jobportal.config;

import com.jobportal.security.CurrentUserInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// General web plumbing: registers CurrentUserInterceptor for every request except static
// assets and the error dispatch (Section 4.6). Also the home for
// @EnableConfigurationProperties(AppProperties.class), since AppProperties is a plain
// record and nothing else in this module scans for @ConfigurationProperties classes -
// PasswordResetProperties (Section 16 #1) is registered alongside it for the same reason;
// see that class's own comment for why it does not belong on SecurityConfig instead.
@Configuration
@EnableConfigurationProperties({AppProperties.class, PasswordResetProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserInterceptor currentUserInterceptor;

    public WebMvcConfig(CurrentUserInterceptor currentUserInterceptor) {
        this.currentUserInterceptor = currentUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(currentUserInterceptor)
                .excludePathPatterns("/webjars/**", "/css/**", "/js/**", "/images/**", "/error");
    }
}
