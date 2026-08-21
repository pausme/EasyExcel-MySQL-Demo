package com.huang.demo.excel.controller;

import com.huang.demo.common.audit.service.DownloadAuditService;
import com.huang.demo.common.idempotency.service.IdempotencyService;
import com.huang.demo.excel.api.dto.ImportErrorPreviewResponse;
import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.api.dto.ImportPrecheckResponse;
import com.huang.demo.excel.api.dto.ImportTaskResponse;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.StudentImportTaskService;
import com.huang.demo.excel.service.StudentService;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.service.TaskOwnerResolver;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/excel")
public class ExcelDemoController {

    private static final Logger log = LoggerFactory.getLogger(ExcelDemoController.class);

    private final StudentService studentService;
    private final ExcelDemoProperties properties;
    private final ExportTaskService exportTaskService;
    private final StudentImportTaskService studentImportTaskService;
    private final TaskOwnerResolver taskOwnerResolver;
    private final DownloadAuditService downloadAuditService;
    private final IdempotencyService idempotencyService;

    public ExcelDemoController(StudentService studentService,
                               ExcelDemoProperties properties,
                               ExportTaskService exportTaskService,
                               StudentImportTaskService studentImportTaskService,
                               TaskOwnerResolver taskOwnerResolver,
                               DownloadAuditService downloadAuditService,
                               IdempotencyService idempotencyService) {
        this.studentService = studentService;
        this.properties = properties;
        this.exportTaskService = exportTaskService;
        this.studentImportTaskService = studentImportTaskService;
        this.taskOwnerResolver = taskOwnerResolver;
        this.downloadAuditService = downloadAuditService;
        this.idempotencyService = idempotencyService;
    }

    @ApiOperation("查询学生数据总数")
    @GetMapping("/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", studentService.count());
        return result;
    }

    @ApiOperation("生成指定数量的学生演示数据")
    @PostMapping("/seed/{count}")
    public Map<String, Object> seed(@PathVariable("count") int count) {
        long start = System.currentTimeMillis();
        int inserted = studentService.seedDemoData(count);
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("inserted", inserted);
        result.put("count", studentService.count());
        result.put("elapsedMs", elapsed);
        log.info("seed api finished, requested={}, inserted={}, elapsedMs={}", count, inserted, elapsed);
        return result;
    }

    @ApiOperation("提交学生数据导出任务")
    @PostMapping("/export")
    public ExportTaskResponse submitExport(@RequestParam(value = "format", required = false) String format,
                                           @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String idempotencyKey,
                                           HttpServletRequest request) {
        try {
            String ownerId = taskOwnerResolver.resolve(request);
            return executeIdempotent(ownerId, "EXCEL_EXPORT_SUBMIT", idempotencyKey,
                    idempotencyService.fingerprint("format", format),
                    ExportTaskResponse.class,
                    () -> ExportTaskResponse.from(exportTaskService.submitExport(ownerId, format)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("查询学生数据导出任务状态")
    @GetMapping("/export/{taskId}")
    public ExportTaskResponse exportStatus(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        return ExportTaskResponse.from(findMyExportTask(taskId, request));
    }

    @ApiOperation("下载已完成的学生数据导出文件")
    @GetMapping("/export/{taskId}/download")
    public ResponseEntity<Void> downloadExport(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        ExportTask task = findMyExportTask(taskId, request);
        if (task.getStatus() != ExportTaskStatus.SUCCESS) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "导出任务尚未完成");
        }

        String downloadUrl = exportTaskService.createDownloadUrl(task)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导出文件不存在"));
        downloadAuditService.recordSignedDownload(
                taskOwnerResolver.resolve(request),
                "EXPORT",
                task.getTaskId(),
                task.getObjectKey(),
                task.getFileName(),
                request);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }

    @ApiOperation("下载学生导入模板")
    @GetMapping("/template")
    public void downloadImportTemplate(HttpServletResponse response) throws IOException {
        long start = System.currentTimeMillis();
        setExcelDownloadHeaders(response, "student-import-template");
        studentService.writeImportTemplate(response.getOutputStream());
        log.info("download import template finished, elapsedMs={}", System.currentTimeMillis() - start);
    }

    @ApiOperation("提交学生数据异步导入任务")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportTaskResponse importExcel(@RequestParam("file") MultipartFile file,
                                          @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String idempotencyKey,
                                          HttpServletRequest request) throws IOException {
        long start = System.currentTimeMillis();
        try {
            String ownerId = taskOwnerResolver.resolve(request);
            ImportTaskResponse response = executeIdempotentWithIOException(ownerId, "EXCEL_IMPORT_SUBMIT", idempotencyKey,
                    idempotencyService.fingerprint(
                            "file",
                            file == null ? null : file.getOriginalFilename(),
                            file == null ? null : file.getContentType(),
                            file == null ? null : file.getSize()),
                    ImportTaskResponse.class,
                    () -> {
                        AsyncTaskRecord task = studentImportTaskService.submitImport(file, ownerId);
                        log.info("import task submitted, taskId={}, fileName={}, elapsedMs={}",
                                task.getTaskId(), file.getOriginalFilename(), System.currentTimeMillis() - start);
                        return ImportTaskResponse.from(AsyncTaskResponse.from(task));
                    });
            return response;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("预检学生数据导入文件")
    @PostMapping(value = "/import/precheck", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPrecheckResponse precheckImport(@RequestParam("file") MultipartFile file) throws IOException {
        return studentImportTaskService.precheckImport(file);
    }

    @ApiOperation("查询学生数据导入任务状态")
    @GetMapping("/import/{taskId}")
    public ImportTaskResponse importStatus(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        return studentImportTaskService.findImportTask(taskId, taskOwnerResolver.resolve(request))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导入任务不存在"));
    }

    @ApiOperation("下载学生数据导入错误明细文件")
    @GetMapping("/import/{taskId}/error-file")
    public ResponseEntity<Void> downloadImportErrorFile(@PathVariable("taskId") String taskId,
                                                        HttpServletRequest request) {
        String downloadUrl = studentImportTaskService.createErrorFileDownloadUrl(
                        taskId, taskOwnerResolver.resolve(request))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导入错误明细文件不存在"));
        downloadAuditService.recordSignedDownload(
                taskOwnerResolver.resolve(request),
                "IMPORT_ERROR",
                taskId,
                null,
                null,
                request);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }

    @ApiOperation("预览学生数据导入错误明细")
    @GetMapping("/import/{taskId}/errors")
    public ImportErrorPreviewResponse previewImportErrors(@PathVariable("taskId") String taskId,
                                                          @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
                                                          HttpServletRequest request) {
        return studentImportTaskService.previewImportErrors(taskId, taskOwnerResolver.resolve(request), limit)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导入错误明细不存在"));
    }

    private void setExcelDownloadHeaders(HttpServletResponse response, String fileName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String downloadFileName = fileName.endsWith(".xlsx") ? fileName : fileName + ".xlsx";
        String encodedFileName = URLEncoder.encode(downloadFileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=UTF-8''" + encodedFileName);
    }

    private ExportTask findMyExportTask(String taskId, HttpServletRequest request) {
        String ownerId = taskOwnerResolver.resolve(request);
        ExportTask task = exportTaskService.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导出任务不存在"));
        if (!ownerId.equals(task.getOwnerId())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "导出任务不存在");
        }
        return task;
    }

    private <T> T executeIdempotent(String ownerId,
                                    String operation,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    Class<T> responseType,
                                    com.huang.demo.common.idempotency.service.IdempotentAction<T> action) {
        try {
            return idempotencyService.execute(ownerId, operation, idempotencyKey, requestFingerprint, responseType, action);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("执行幂等请求失败", ex);
        }
    }

    private <T> T executeIdempotentWithIOException(String ownerId,
                                                   String operation,
                                                   String idempotencyKey,
                                                   String requestFingerprint,
                                                   Class<T> responseType,
                                                   com.huang.demo.common.idempotency.service.IdempotentAction<T> action)
            throws IOException {
        try {
            return idempotencyService.execute(ownerId, operation, idempotencyKey, requestFingerprint, responseType, action);
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("执行幂等请求失败", ex);
        }
    }
}
