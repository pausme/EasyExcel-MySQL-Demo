package com.huang.demo.common.compensation.service;

import com.huang.demo.common.compensation.api.dto.CompensationActionResponse;
import com.huang.demo.common.compensation.api.dto.CompensationPageQueryRequest;
import com.huang.demo.common.compensation.api.dto.CompensationPageResponse;

public interface CompensationManagementService {

    CompensationPageResponse page(CompensationPageQueryRequest request);

    CompensationActionResponse retry(String compensationId);

    CompensationActionResponse ignore(String compensationId);
}
