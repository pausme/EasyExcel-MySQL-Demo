package com.huang.demo.excel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.excel")
@Getter
@Setter
public class ExcelDemoProperties {

    private int exportPageSize;

    private int sheetRowLimit;

    private int importBatchSize;

    private int insertBatchSize;

    private int demoSeedCount;

    private String exportTempDir;

    private int exportFileRetentionHours = 24;

}
