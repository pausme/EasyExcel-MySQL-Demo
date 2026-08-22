package com.huang.demo.common.audit.service;

import com.huang.demo.common.audit.api.dto.DownloadAuditPageQueryRequest;
import com.huang.demo.common.audit.api.dto.DownloadAuditPageResponse;

public interface DownloadAuditQueryService {

    DownloadAuditPageResponse page(String currentOwnerId, boolean admin, DownloadAuditPageQueryRequest request);
}
