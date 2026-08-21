package com.huang.demo.common.compensation.service;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;

public interface CompensationService {

    CompensationRecord recordPending(String bizType,
                                     String bizId,
                                     String failureType,
                                     String payload);

    void markRunning(String compensationId);

    void markSuccess(String compensationId);

    void markFailed(String compensationId, String lastError);

    static CompensationService noop() {
        return new CompensationService() {
            @Override
            public CompensationRecord recordPending(String bizType, String bizId,
                                                     String failureType, String payload) {
                return null;
            }

            @Override
            public void markRunning(String compensationId) {
            }

            @Override
            public void markSuccess(String compensationId) {
            }

            @Override
            public void markFailed(String compensationId, String lastError) {
            }
        };
    }
}
