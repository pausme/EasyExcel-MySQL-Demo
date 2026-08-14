package com.huang.demo.excel.domain.model;

import com.huang.demo.excel.model.StudentImportErrorRow;

import java.util.Collections;
import java.util.List;

public class StudentImportValidationException extends RuntimeException {

    private final List<StudentImportErrorRow> errorRows;

    public StudentImportValidationException(String message, List<StudentImportErrorRow> errorRows) {
        super(message);
        this.errorRows = errorRows == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(errorRows);
    }

    public List<StudentImportErrorRow> getErrorRows() {
        return errorRows;
    }
}
