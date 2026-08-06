package com.huang.demo.task.domain.model;

public class TaskCanceledException extends RuntimeException {

    public TaskCanceledException(String message) {
        super(message);
    }
}
