package com.huang.demo.excel.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.listener.StudentImportListener;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.service.StudentService;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/excel")
public class ExcelDemoController {

    private static final Logger log = LoggerFactory.getLogger(ExcelDemoController.class);

    private final StudentService studentService;
    private final ExcelDemoProperties properties;

    public ExcelDemoController(StudentService studentService, ExcelDemoProperties properties) {
        this.studentService = studentService;
        this.properties = properties;
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

    @ApiOperation("导出学生数据 Excel")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        long start = System.currentTimeMillis();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String fileName = URLEncoder.encode("student-demo", StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

        int total = studentService.count();
        int sheetRowLimit = properties.getSheetRowLimit();
        int exportPageSize = properties.getExportPageSize();
        int sheetCount = Math.max(1, (total + sheetRowLimit - 1) / sheetRowLimit);
        int exported = 0;

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream(), StudentExcelRow.class).build()) {
            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
                long sheetStartTime = System.currentTimeMillis();
                int sheetStart = sheetIndex * sheetRowLimit;
                int sheetEnd = Math.min(total, sheetStart + sheetRowLimit);
                int sheetRows = 0;
                WriteSheet writeSheet = EasyExcel.writerSheet(sheetIndex, "Sheet" + (sheetIndex + 1)).build();
                for (int offset = sheetStart; offset < sheetEnd; offset += exportPageSize) {
                    int limit = Math.min(exportPageSize, sheetEnd - offset);
                    List<StudentExcelRow> rows = studentService.listPage(offset, limit);
                    writer.write(rows, writeSheet);
                    sheetRows += rows.size();
                    exported += rows.size();
                }
                log.info("export sheet finished, sheet={}, rows={}, elapsedMs={}",
                        sheetIndex + 1, sheetRows, System.currentTimeMillis() - sheetStartTime);
            }
        }
        log.info("export api finished, total={}, exported={}, sheetCount={}, elapsedMs={}",
                total, exported, sheetCount, System.currentTimeMillis() - start);
    }

    @ApiOperation("导入学生数据 Excel")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        long start = System.currentTimeMillis();
        StudentImportListener listener = new StudentImportListener(studentService, properties.getImportBatchSize());
        EasyExcel.read(file.getInputStream(), StudentExcelRow.class, listener).sheet().doRead();
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
}
