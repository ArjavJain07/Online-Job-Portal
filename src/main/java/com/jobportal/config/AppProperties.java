package com.jobportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds the app.* properties of application.properties (Section 10.1). Registered by
// @EnableConfigurationProperties(AppProperties.class) on WebMvcConfig, since this is a
// record (constructor binding) rather than a component-scanned bean.
@ConfigurationProperties(prefix = "app")
public record AppProperties(String uploadDir, Seed seed, Demo demo) {

    public record Seed(boolean demoData, String adminEmail, String adminPassword) {
    }

    public record Demo(boolean showCredentials) {
    }
}
