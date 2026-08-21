package com.huang.demo.common.idempotency.service;

public interface IdempotencyService {

    String HEADER_NAME = "Idempotency-Key";

    <T> T execute(String ownerId,
                  String operation,
                  String idempotencyKey,
                  String requestFingerprint,
                  Class<T> responseType,
                  IdempotentAction<T> action) throws Exception;

    String fingerprint(Object... values);
}
