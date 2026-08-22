package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.config.StudentImportValidationProperties;
import com.huang.demo.excel.domain.model.StudentImportRowView;
import com.huang.demo.excel.service.StudentImportValidationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class StudentImportValidationServiceImpl implements StudentImportValidationService {

    private final Map<String, StudentImportValidationProperties.FieldRule> fieldRuleMap;

    public StudentImportValidationServiceImpl(StudentImportValidationProperties properties) {
        this.fieldRuleMap = buildFieldRuleMap(properties);
    }

    @Override
    public List<String> validate(StudentImportRowView row) {
        List<String> errors = new ArrayList<String>();
        validateRow(row, errors);
        return errors;
    }

    @Override
    public List<String> validatePreview(StudentImportRowView row,
                                        int rowNo,
                                        Map<String, Map<String, Integer>> uniqueFieldFirstRows) {
        List<String> errors = new ArrayList<String>();
        validateRow(row, errors);
        addPreviewDuplicateError(row, rowNo, uniqueFieldFirstRows, errors);
        return errors;
    }

    private void validateRow(StudentImportRowView row, List<String> errors) {
        StudentImportRowView safeRow = row == null ? emptyRow() : row;
        for (StudentImportValidationProperties.FieldRule rule : fieldRuleMap.values()) {
            validateField(rule, safeRow, errors);
        }
    }

    private void validateField(StudentImportValidationProperties.FieldRule rule,
                               StudentImportRowView row,
                               List<String> errors) {
        if (rule == null || isBlank(rule.getFieldName())) {
            return;
        }
        String label = isBlank(rule.getLabel()) ? rule.getFieldName() : rule.getLabel().trim();
        if ("age".equals(rule.getFieldName())) {
            validateIntegerField(label, row.getAge(), rule, errors);
            return;
        }
        String value = valueByField(rule.getFieldName(), row);
        validateStringField(label, value, rule, errors);
    }

    private void validateStringField(String label,
                                     String value,
                                     StudentImportValidationProperties.FieldRule rule,
                                     List<String> errors) {
        String normalized = value == null ? null : value.trim();
        if (isBlank(normalized)) {
            if (rule.isRequired()) {
                errors.add(label + "不能为空");
            }
            return;
        }
        if (rule.getMinLength() != null && normalized.length() < rule.getMinLength()) {
            errors.add(label + "长度不能小于" + rule.getMinLength());
        }
        if (rule.getMaxLength() != null && normalized.length() > rule.getMaxLength()) {
            errors.add(label + "长度不能超过" + rule.getMaxLength());
        }
        if (rule.getPattern() != null && !rule.getPattern().trim().isEmpty()
                && !Pattern.compile(rule.getPattern()).matcher(normalized).matches()) {
            errors.add(label + "格式不正确");
        }
        if (rule.getAllowedValues() != null && !rule.getAllowedValues().isEmpty()
                && !rule.getAllowedValues().contains(normalized)) {
            errors.add(label + "必须是" + String.join("、", rule.getAllowedValues()));
        }
    }

    private void validateIntegerField(String label,
                                      Integer value,
                                      StudentImportValidationProperties.FieldRule rule,
                                      List<String> errors) {
        if (value == null) {
            if (rule.isRequired()) {
                errors.add(label + "不能为空");
            }
            return;
        }
        if (rule.getMinValue() != null && value < rule.getMinValue()) {
            errors.add(label + "不能小于" + rule.getMinValue());
        }
        if (rule.getMaxValue() != null && value > rule.getMaxValue()) {
            errors.add(label + "不能大于" + rule.getMaxValue());
        }
    }

    private void addPreviewDuplicateError(StudentImportRowView row,
                                          int rowNo,
                                          Map<String, Map<String, Integer>> uniqueFieldFirstRows,
                                          List<String> errors) {
        if (row == null || uniqueFieldFirstRows == null) {
            return;
        }
        for (StudentImportValidationProperties.FieldRule rule : fieldRuleMap.values()) {
            if (rule == null || !rule.isUniqueInFile() || isBlank(rule.getFieldName())) {
                continue;
            }
            String value = valueByField(rule.getFieldName(), row);
            if (isBlank(value)) {
                continue;
            }
            String normalizedValue = value.trim();
            Map<String, Integer> firstRows = uniqueFieldFirstRows.get(rule.getFieldName());
            if (firstRows == null) {
                firstRows = new LinkedHashMap<String, Integer>();
                uniqueFieldFirstRows.put(rule.getFieldName(), firstRows);
            }
            Integer firstRowNo = firstRows.putIfAbsent(normalizedValue, rowNo);
            if (firstRowNo != null) {
                String label = isBlank(rule.getLabel()) ? rule.getFieldName() : rule.getLabel().trim();
                errors.add(label + "重复，首次出现行号=" + firstRowNo);
            }
        }
    }

    private Map<String, StudentImportValidationProperties.FieldRule> buildFieldRuleMap(StudentImportValidationProperties properties) {
        List<StudentImportValidationProperties.FieldRule> configuredRules =
                properties == null || properties.getFields() == null || properties.getFields().isEmpty()
                        ? buildDefaultRules()
                        : properties.getFields();
        Map<String, StudentImportValidationProperties.FieldRule> result =
                new LinkedHashMap<String, StudentImportValidationProperties.FieldRule>();
        for (StudentImportValidationProperties.FieldRule rule : configuredRules) {
            if (rule == null || isBlank(rule.getFieldName())) {
                continue;
            }
            result.put(rule.getFieldName(), rule);
        }
        return result;
    }

    private List<StudentImportValidationProperties.FieldRule> buildDefaultRules() {
        List<StudentImportValidationProperties.FieldRule> rules =
                new ArrayList<StudentImportValidationProperties.FieldRule>();
        rules.add(rule("studentNo", "学号", true, 32, null, null, null, null, null, true));
        rules.add(rule("name", "姓名", true, 64, null, null, null, null, null, false));
        rules.add(rule("age", "年龄", false, null, null, 0, 150, null, null, false));
        rules.add(rule("gender", "性别", false, 16, null, null, null, null, null, false));
        rules.add(rule("className", "班级", false, 64, null, null, null, null, null, false));
        rules.add(rule("email", "邮箱", false, 128, null, null, null, "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", null, false));
        rules.add(rule("birthday", "生日", false, 32, null, null, null, "^\\d{4}-\\d{2}-\\d{2}$", null, false));
        return rules;
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
        rule.setAllowedValues(allowedValues == null ? new ArrayList<String>() : new ArrayList<String>(allowedValues));
        rule.setUniqueInFile(uniqueInFile);
        return rule;
    }

    private String valueByField(String fieldName, StudentImportRowView row) {
        if ("studentNo".equals(fieldName)) {
            return row.getStudentNo();
        }
        if ("name".equals(fieldName)) {
            return row.getName();
        }
        if ("gender".equals(fieldName)) {
            return row.getGender();
        }
        if ("className".equals(fieldName)) {
            return row.getClassName();
        }
        if ("email".equals(fieldName)) {
            return row.getEmail();
        }
        if ("birthday".equals(fieldName)) {
            return row.getBirthday();
        }
        return null;
    }

    private StudentImportRowView emptyRow() {
        return new StudentImportRowView() {
            @Override
            public String getStudentNo() {
                return null;
            }

            @Override
            public String getName() {
                return null;
            }

            @Override
            public Integer getAge() {
                return null;
            }

            @Override
            public String getGender() {
                return null;
            }

            @Override
            public String getClassName() {
                return null;
            }

            @Override
            public String getEmail() {
                return null;
            }

            @Override
            public String getBirthday() {
                return null;
            }
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
