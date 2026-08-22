package com.huang.demo.file.service;

import com.huang.demo.file.domain.model.FileReconciliationResult;

public interface FileObjectReconciliationService {

    FileReconciliationResult reconcileOnceWithLock();

    FileReconciliationResult reconcileOnce();
}
