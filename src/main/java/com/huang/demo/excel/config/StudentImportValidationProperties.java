package com.huang.demo.excel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.excel.import-validation")
@Getter
@Setter
public class StudentImportValidationProperties {

    private List<FieldRule> fields = new ArrayList<FieldRule>();

    @Getter
    @Setter
    public static class FieldRule {

        private String fieldName;

        private String label;

        private boolean required;

        private Integer maxLength;

        private Integer minLength;

        private Integer minValue;

        private Integer maxValue;

        private String pattern;

        private List<String> allowedValues = new ArrayList<String>();

        private boolean uniqueInFile;
    }
}
