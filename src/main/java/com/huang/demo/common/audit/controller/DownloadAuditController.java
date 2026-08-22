package com.huang.demo.common.audit.controller;

import com.huang.demo.common.audit.api.dto.DownloadAuditPageQueryRequest;
import com.huang.demo.common.audit.api.dto.DownloadAuditPageResponse;
import com.huang.demo.common.audit.service.DownloadAuditQueryService;
import com.huang.demo.security.service.PermissionService;
import com.huang.demo.task.service.TaskOwnerResolver;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/download-audits")
public class DownloadAuditController {

    private final DownloadAuditQueryService downloadAuditQueryService;
    private final TaskOwnerResolver taskOwnerResolver;
    private final PermissionService permissionService;

    public DownloadAuditController(DownloadAuditQueryService downloadAuditQueryService,
                                   TaskOwnerResolver taskOwnerResolver,
                                   PermissionService permissionService) {
        this.downloadAuditQueryService = downloadAuditQueryService;
        this.taskOwnerResolver = taskOwnerResolver;
        this.permissionService = permissionService;
    }

    @ApiOperation("分页查询自己的下载审计记录")
    @PostMapping("/page")
    public DownloadAuditPageResponse page(@Valid @RequestBody(required = false) DownloadAuditPageQueryRequest request,
                                          HttpServletRequest httpServletRequest) {
        try {
            return downloadAuditQueryService.page(
                    taskOwnerResolver.resolve(httpServletRequest), permissionService.isAdmin(), request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
