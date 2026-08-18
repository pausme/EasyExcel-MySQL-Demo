package com.huang.demo.cleanup.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CleanupResult {

    private final int expiredTasks;

    private final int uploadTasks;

    private final int deletedFiles;

    private final int importStageRows;

    private final int importVersionRows;
}
