package com.huang.demo.common.compensation.service;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;

public interface CompensationHandler {

    boolean supports(CompensationRecord record);

    void handle(CompensationRecord record);
}
