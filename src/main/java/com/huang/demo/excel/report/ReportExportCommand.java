package com.huang.demo.excel.report;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;

@Getter
@Builder
public class ReportExportCommand<P> {

    private final String taskId;

    private final P params;

    private final Long snapshotMaxId;

    private final int pageSize;

    private final int sheetRowLimit;

    private final Path filePath;

    private final ReportCancelChecker cancelChecker;

    private final ReportProgressUpdater progressUpdater;
}
