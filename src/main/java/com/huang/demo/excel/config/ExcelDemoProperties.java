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

    private int importMaxRowsPerTask = 200000;

    private long importMaxFileSizeForAsync = 104857600L;

    private int importMergeChunkSize = 5000;

    private boolean importAutoRecoveryEnabled = false;

    private int importWorkerCount = 4;

    private int importMaxConcurrentTasks = 1;

    private int importQueueCapacity = 20;

    private int importExecutorQueueCapacity = 20;

    private int importTaskCorePoolSize = 1;

    private int importTaskMaxPoolSize = 1;

    private int importTaskQueueCapacity = 10;

    private int importAwaitTerminationSeconds = 30;

    private int importWorkerFinishWaitSeconds = 0;

    private int importTransactionTimeoutSeconds = 60;

    private int importMaxRetryTimes = 3;

    private long importRetryBackoffMillis = 200L;

    private int importProgressLogInterval = 50;

    private boolean importBatchSortEnabled = true;

    private int insertBatchSize;

    private int demoSeedCount;

    private String exportTempDir;

    private String importTempDir;

    private int exportFileRetentionHours = 24;

    private int exportCorePoolSize = 2;

    private int exportMaxPoolSize = 2;

    private int exportQueueCapacity = 10;

    private int exportAwaitTerminationSeconds = 30;

    private String exportRejectedExecutionPolicy = "abort";

    private boolean taskCompensationEnabled = true;

    private long taskCompensationInitialDelayMillis = 600000L;

    private long taskCompensationFixedDelayMillis = 3600000L;

    private int taskCompensationBatchSize = 100;

    private String taskCompensationLockKey = "task:compensation:lock";

    private int taskCompensationLockTtlSeconds = 300;

    private boolean initEnabled = true;

}
