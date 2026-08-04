package com.huang.demo.excel.service;

import com.huang.demo.excel.domain.model.ExportTask;

import java.util.Optional;

public interface ExportTaskService {

    ExportTask submitExport();

    Optional<ExportTask> findTask(String taskId);

    Optional<String> createDownloadUrl(ExportTask task);
}
