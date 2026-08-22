package com.huang.demo.excel.controller;

import com.huang.demo.common.api.dto.CursorPageResponse;
import com.huang.demo.excel.api.dto.StudentCursorQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageResponse;
import com.huang.demo.excel.api.dto.StudentResponse;
import com.huang.demo.excel.service.StudentQueryService;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentQueryService studentQueryService;

    public StudentController(StudentQueryService studentQueryService) {
        this.studentQueryService = studentQueryService;
    }

    @ApiOperation("分页查询学生数据")
    @PostMapping("/page")
    public StudentPageResponse page(@Valid @RequestBody(required = false) StudentPageQueryRequest request) {
        try {
            return studentQueryService.page(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("游标分页查询学生数据")
    @PostMapping("/cursor-page")
    public CursorPageResponse<StudentResponse> cursorPage(
            @Valid @RequestBody(required = false) StudentCursorQueryRequest request) {
        try {
            return studentQueryService.cursorPage(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
