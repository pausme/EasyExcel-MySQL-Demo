package com.huang.demo.file.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.file")
@Getter
@Setter
public class FileCenterProperties {

    private boolean initEnabled = true;

    private String objectPrefix = "files/general";

    private String multipartObjectPrefix = "files/multipart";

    private int downloadUrlExpireMinutes = 30;

    private int uploadUrlExpireMinutes = 30;

    private long multipartPartSize = 8L * 1024L * 1024L;

    private int multipartMaxPartCount = 1000;

    private int maxPageSize = 100;
}
