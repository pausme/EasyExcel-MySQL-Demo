package com.huang.demo.security.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security")
@Getter
@Setter
public class ApiSecurityProperties {

    private boolean demoMode = true;

    private String demoUserToken = "demo-user-token";

    private String demoAdminToken = "demo-admin-token";

    private String demoDefaultUserId = "anonymous";
}
