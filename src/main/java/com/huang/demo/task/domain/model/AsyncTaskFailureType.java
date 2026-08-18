package com.huang.demo.task.domain.model;

public enum AsyncTaskFailureType {

    VALIDATION_ERROR,
    DEPENDENCY_ERROR,
    RESOURCE_LIMIT,
    SYSTEM_ERROR,
    CANCELED
}
