package com.huang.demo.ops.controller;

import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.SecurityErrorCode;
import com.huang.demo.ops.api.dto.OpsOverviewResponse;
import com.huang.demo.ops.service.OpsDashboardService;
import com.huang.demo.security.service.PermissionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsDashboardControllerTest {

    @Test
    void overviewRequiresAdmin() {
        OpsDashboardService opsDashboardService = mock(OpsDashboardService.class);
        PermissionService permissionService = mock(PermissionService.class);
        doThrow(new BusinessException(SecurityErrorCode.FORBIDDEN, "需要管理员权限"))
                .when(permissionService).requireAdmin();
        OpsDashboardController controller = new OpsDashboardController(opsDashboardService, permissionService);

        BusinessException exception = assertThrows(BusinessException.class, controller::overview);

        assertEquals(SecurityErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(permissionService).requireAdmin();
    }

    @Test
    void overviewReturnsServiceResponseForAdmin() {
        OpsDashboardService opsDashboardService = mock(OpsDashboardService.class);
        PermissionService permissionService = mock(PermissionService.class);
        OpsOverviewResponse expected = OpsOverviewResponse.builder()
                .generatedAt(LocalDateTime.now())
                .todayTaskCount(1L)
                .build();
        when(opsDashboardService.overview()).thenReturn(expected);
        OpsDashboardController controller = new OpsDashboardController(opsDashboardService, permissionService);

        OpsOverviewResponse response = controller.overview();

        assertSame(expected, response);
        verify(permissionService).requireAdmin();
        verify(opsDashboardService).overview();
    }
}
