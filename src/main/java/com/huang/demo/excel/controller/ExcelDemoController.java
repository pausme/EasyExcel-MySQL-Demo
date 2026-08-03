package com.huang.demo.excel.controller;

import com.alibaba.excel.EasyExcel;
import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.listener.StudentImportListener;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.StudentService;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
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

    public ExcelDemoController(StudentService studentService,
                               ExcelDemoProperties properties,
                               ExportTaskService exportTaskService) {
        this.studentService = studentService;
        this.properties = properties;
        this.exportTaskService = exportTaskService;
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
    public ExportTaskResponse submitExport() {
        return ExportTaskResponse.from(exportTaskService.submitExport());
    }

    @ApiOperation("查询学生数据导出任务状态")
    @GetMapping("/export/{taskId}")
    public ExportTaskResponse exportStatus(@PathVariable("taskId") String taskId) {
        ExportTask task = exportTaskService.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导出任务不存在"));
        return ExportTaskResponse.from(task);
    }

    @ApiOperation("下载已完成的学生数据导出文件")
    @GetMapping("/export/{taskId}/download")
    public ResponseEntity<Void> downloadExport(@PathVariable("taskId") String taskId) {
        ExportTask task = exportTaskService.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导出任务不存在"));
        if (task.getStatus() != ExportTaskStatus.SUCCESS) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "导出任务尚未完成");
        }

        String downloadUrl = exportTaskService.createDownloadUrl(task)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "导出文件不存在"));
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

    @ApiOperation("导入学生数据 Excel")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        long start = System.currentTimeMillis();
        StudentImportListener listener = new StudentImportListener(studentService, properties.getImportBatchSize());
        EasyExcel.read(file.getInputStream(), StudentExcelRow.class, listener).doReadAll();
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("imported", listener.getImportedCount());
        result.put("batchCount", listener.getBatchCount());
        result.put("count", studentService.count());
        result.put("elapsedMs", elapsed);
        log.info("import api finished, fileName={}, imported={}, batchCount={}, elapsedMs={}",
                file.getOriginalFilename(), listener.getImportedCount(), listener.getBatchCount(), elapsed);
        return result;
    }

    private void setExcelDownloadHeaders(HttpServletResponse response, String fileName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=UTF-8''" + encodedFileName + ".xlsx");
    }
}
