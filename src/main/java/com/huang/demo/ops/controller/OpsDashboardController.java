package com.huang.demo.ops.controller;

import com.huang.demo.ops.api.dto.OpsOverviewResponse;
import com.huang.demo.ops.service.OpsDashboardService;
import com.huang.demo.security.service.PermissionService;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ops")
public class OpsDashboardController {

    private final OpsDashboardService opsDashboardService;
    private final PermissionService permissionService;

    public OpsDashboardController(OpsDashboardService opsDashboardService, PermissionService permissionService) {
        this.opsDashboardService = opsDashboardService;
        this.permissionService = permissionService;
    }

    @ApiOperation("管理员查询运维首页聚合数据")
    @GetMapping("/overview")
    public OpsOverviewResponse overview() {
        permissionService.requireAdmin();
        return opsDashboardService.overview();
    }
}
