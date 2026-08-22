package com.huang.demo.common.audit.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DownloadAuditPageResponse {

    private final long total;

    private final int pageNo;

    private final int pageSize;

    private final List<DownloadAuditResponse> records;
}
