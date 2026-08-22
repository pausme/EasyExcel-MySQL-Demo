package com.huang.demo.common.compensation.controller;

import com.huang.demo.common.compensation.api.dto.CompensationActionResponse;
import com.huang.demo.common.compensation.api.dto.CompensationPageQueryRequest;
import com.huang.demo.common.compensation.api.dto.CompensationPageResponse;
import com.huang.demo.common.compensation.service.CompensationManagementService;
import com.huang.demo.security.service.PermissionService;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/compensations")
public class CompensationManagementController {

    private final CompensationManagementService compensationManagementService;
    private final PermissionService permissionService;

    public CompensationManagementController(CompensationManagementService compensationManagementService,
                                            PermissionService permissionService) {
        this.compensationManagementService = compensationManagementService;
        this.permissionService = permissionService;
    }

    @ApiOperation("管理员分页查询补偿记录")
    @PostMapping("/page")
    public CompensationPageResponse page(@Valid @RequestBody(required = false) CompensationPageQueryRequest request) {
        permissionService.requireAdmin();
        try {
            return compensationManagementService.page(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("管理员手动重试补偿记录")
    @PostMapping("/{compensationId}/retry")
    public CompensationActionResponse retry(@PathVariable("compensationId") String compensationId) {
        permissionService.requireAdmin();
        try {
            return compensationManagementService.retry(compensationId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @ApiOperation("管理员忽略补偿记录")
    @PostMapping("/{compensationId}/ignore")
    public CompensationActionResponse ignore(@PathVariable("compensationId") String compensationId) {
        permissionService.requireAdmin();
        try {
            return compensationManagementService.ignore(compensationId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
