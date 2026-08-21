package com.huang.demo.common.audit.service;

import javax.servlet.http.HttpServletRequest;

public interface DownloadAuditService {

    void recordSignedDownload(String ownerId,
                              String resourceType,
                              String resourceId,
                              String objectKey,
                              String fileName,
                              HttpServletRequest request);
}
