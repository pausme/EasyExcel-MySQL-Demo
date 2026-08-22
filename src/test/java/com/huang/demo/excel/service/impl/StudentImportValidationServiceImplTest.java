package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.config.StudentImportValidationProperties;
import com.huang.demo.excel.model.StudentExcelRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentImportValidationServiceImplTest {

    @Test
    void validateUsesConfiguredFieldRules() {
        StudentImportValidationProperties properties = new StudentImportValidationProperties();
        List<StudentImportValidationProperties.FieldRule> fields = new ArrayList<StudentImportValidationProperties.FieldRule>();
        fields.add(rule("studentNo", "学号", true, 32, null, null, null, null, null, true));
        fields.add(rule("email", "邮箱", false, 128, null, null, null, "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", null, false));
        properties.setFields(fields);

        StudentImportValidationServiceImpl service = new StudentImportValidationServiceImpl(properties);
        StudentExcelRow row = StudentExcelRow.builder()
                .studentNo("")
                .email("bad-email")
                .build();

        List<String> errors = service.validate(row);

        assertTrue(errors.contains("学号不能为空"));
        assertTrue(errors.contains("邮箱格式不正确"));
    }

    @Test
    void validatePreviewDetectsConfiguredUniqueField() {
        StudentImportValidationProperties properties = new StudentImportValidationProperties();
        List<StudentImportValidationProperties.FieldRule> fields = new ArrayList<StudentImportValidationProperties.FieldRule>();
        fields.add(rule("studentNo", "学号", true, 32, null, null, null, null, null, true));
        properties.setFields(fields);

        StudentImportValidationServiceImpl service = new StudentImportValidationServiceImpl(properties);
        Map<String, Map<String, Integer>> uniqueFieldFirstRows = new HashMap<String, Map<String, Integer>>();
        StudentExcelRow firstRow = StudentExcelRow.builder().studentNo("S10001").build();
        StudentExcelRow duplicatedRow = StudentExcelRow.builder().studentNo("S10001").build();

        List<String> firstErrors = service.validatePreview(firstRow, 2, uniqueFieldFirstRows);
        List<String> duplicateErrors = service.validatePreview(duplicatedRow, 3, uniqueFieldFirstRows);

        assertTrue(firstErrors.isEmpty());
        assertTrue(duplicateErrors.get(0).contains("学号重复"));
        assertTrue(duplicateErrors.get(0).contains("首次出现行号=2"));
    }

    private StudentImportValidationProperties.FieldRule rule(String fieldName,
                                                             String label,
                                                             boolean required,
                                                             Integer maxLength,
                                                             Integer minLength,
                                                             Integer minValue,
                                                             Integer maxValue,
                                                             String pattern,
                                                             List<String> allowedValues,
                                                             boolean uniqueInFile) {
        StudentImportValidationProperties.FieldRule rule = new StudentImportValidationProperties.FieldRule();
        rule.setFieldName(fieldName);
        rule.setLabel(label);
        rule.setRequired(required);
        rule.setMaxLength(maxLength);
        rule.setMinLength(minLength);
        rule.setMinValue(minValue);
        rule.setMaxValue(maxValue);
        rule.setPattern(pattern);
        rule.setAllowedValues(allowedValues);
        rule.setUniqueInFile(uniqueInFile);
        return rule;
    }
}
