package com.huang.demo.excel.controller;

import com.huang.demo.excel.api.dto.ExportTaskResponse;
import com.huang.demo.excel.api.dto.StudentReportRunCreateRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageResponse;
import com.huang.demo.excel.api.dto.StudentReportRunResponse;
import com.huang.demo.excel.api.dto.StudentReportRunUpdateRequest;
import com.huang.demo.excel.service.StudentReportRunService;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.service.TaskOwnerResolver;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/report/student-runs")
public class StudentReportRunController {

    private final StudentReportRunService studentReportRunService;
    private final TaskOwnerResolver taskOwnerResolver;

    public StudentReportRunController(StudentReportRunService studentReportRunService,
                                      TaskOwnerResolver taskOwnerResolver) {
        this.studentReportRunService = studentReportRunService;
        this.taskOwnerResolver = taskOwnerResolver;
    }

    @ApiOperation("分页查询学生报表运行控制")
    @PostMapping("/page")
    public StudentReportRunPageResponse page(@Valid @RequestBody(required = false) StudentReportRunPageQueryRequest request,
                                             HttpServletRequest httpServletRequest) {
        return studentReportRunService.page(taskOwnerResolver.resolve(httpServletRequest), request);
    }

    @ApiOperation("创建学生报表运行控制")
    @PostMapping("/create")
    public StudentReportRunResponse create(@RequestBody StudentReportRunCreateRequest request,
                                           HttpServletRequest httpServletRequest) {
        try {
            return studentReportRunService.create(taskOwnerResolver.resolve(httpServletRequest), request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("查询学生报表运行控制详情")
    @GetMapping("/{runId}")
    public StudentReportRunResponse detail(@PathVariable("runId") String runId,
                                           HttpServletRequest httpServletRequest) {
        try {
            return studentReportRunService.detail(taskOwnerResolver.resolve(httpServletRequest), runId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @ApiOperation("修改学生报表运行控制")
    @PostMapping("/{runId}/update")
    public StudentReportRunResponse update(@PathVariable("runId") String runId,
                                           @RequestBody StudentReportRunUpdateRequest request,
                                           HttpServletRequest httpServletRequest) {
        try {
            return studentReportRunService.update(taskOwnerResolver.resolve(httpServletRequest), runId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("删除学生报表运行控制")
    @PostMapping("/{runId}/delete")
    public Map<String, Object> delete(@PathVariable("runId") String runId,
                                      HttpServletRequest httpServletRequest) {
        try {
            boolean deleted = studentReportRunService.delete(taskOwnerResolver.resolve(httpServletRequest), runId);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("runId", runId);
            response.put("deleted", deleted);
            return response;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @ApiOperation("运行学生报表并创建导出任务")
    @PostMapping("/{runId}/run")
    public ExportTaskResponse run(@PathVariable("runId") String runId,
                                  HttpServletRequest httpServletRequest) {
        try {
            return ExportTaskResponse.from(
                    studentReportRunService.run(taskOwnerResolver.resolve(httpServletRequest), runId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @ApiOperation("分页查询学生报表运行历史任务")
    @PostMapping("/{runId}/tasks")
    public AsyncTaskPageResponse tasks(@PathVariable("runId") String runId,
                                       @Valid @RequestBody(required = false) AsyncTaskPageQueryRequest request,
                                       HttpServletRequest httpServletRequest) {
        try {
            return studentReportRunService.pageTasks(taskOwnerResolver.resolve(httpServletRequest), runId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
