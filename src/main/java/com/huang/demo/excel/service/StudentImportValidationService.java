package com.huang.demo.excel.service;

import com.huang.demo.excel.domain.model.StudentImportRowView;

import java.util.List;
import java.util.Map;

public interface StudentImportValidationService {

    List<String> validate(StudentImportRowView row);

    List<String> validatePreview(StudentImportRowView row,
                                 int rowNo,
                                 Map<String, Map<String, Integer>> uniqueFieldFirstRows);
}
