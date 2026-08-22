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

    private boolean initEnabled = true;

    private boolean demoMode = true;

    private String demoUserToken;

    private String demoAdminToken;

    private String demoDefaultUserId = "anonymous";

    private String jwtSecret;

    private int accessTokenExpireMinutes = 60;

    private int refreshTokenExpireMinutes = 10080;

    private boolean bootstrapAdminEnabled = false;

    private String bootstrapAdminUserId = "admin";

    private String bootstrapAdminUsername;

    private String bootstrapAdminPassword;
}
