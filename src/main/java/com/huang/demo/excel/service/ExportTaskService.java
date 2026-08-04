package com.huang.demo.excel.service;

import com.huang.demo.excel.domain.model.ExportTask;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public interface ExportTaskService {

    ExportTask submitExport();

    Optional<ExportTask> findTask(String taskId);

    Optional<String> createDownloadUrl(ExportTask task);

    Optional<InputStream> openDownloadStream(ExportTask task);

    Optional<Path> findLocalFile(ExportTask task);
}
