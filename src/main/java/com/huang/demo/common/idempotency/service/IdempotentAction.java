package com.huang.demo.common.idempotency.service;

public interface IdempotentAction<T> {

    T execute() throws Exception;
}
