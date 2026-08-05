package com.huang.demo.file.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FileCenterWebConfig implements WebMvcConfigurer {

    private final FileCenterProperties properties;

    public FileCenterWebConfig(FileCenterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.isCorsEnabled()) {
            return;
        }
        registry.addMapping("/api/files/**")
                .allowedOriginPatterns(properties.getCorsAllowedOriginPatterns().toArray(new String[0]))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .maxAge(3600);
    }
}
