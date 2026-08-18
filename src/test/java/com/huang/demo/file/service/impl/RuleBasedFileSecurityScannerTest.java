package com.huang.demo.file.service.impl;

import com.huang.demo.file.config.FileCenterProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleBasedFileSecurityScannerTest {

    @Test
    void scanAllowsPlainTextFile() {
        RuleBasedFileSecurityScanner scanner = newScanner(true);

        assertDoesNotThrow(() -> scanner.scan(
                new ByteArrayInputStream("hello world".getBytes()),
                "demo.txt",
                "text/plain",
                11L));
    }

    @Test
    void scanAllowsZipBasedOfficeFileWithZipHeader() {
        RuleBasedFileSecurityScanner scanner = newScanner(true);

        assertDoesNotThrow(() -> scanner.scan(
                new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0}),
                "demo.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                8L));
    }

    @Test
    void scanRejectsExecutableHeaderEvenIfExtensionLooksSafe() {
        RuleBasedFileSecurityScanner scanner = newScanner(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> scanner.scan(
                new ByteArrayInputStream(new byte[]{'M', 'Z', 0, 0, 0, 0}),
                "evil.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                6L));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("可执行程序"));
    }

    private RuleBasedFileSecurityScanner newScanner(boolean enabled) {
        FileCenterProperties properties = new FileCenterProperties();
        properties.setSecurityScanEnabled(enabled);
        return new RuleBasedFileSecurityScanner(properties);
    }
}
