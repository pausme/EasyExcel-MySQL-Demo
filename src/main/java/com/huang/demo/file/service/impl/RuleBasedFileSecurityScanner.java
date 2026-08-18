package com.huang.demo.file.service.impl;

import com.huang.demo.file.config.FileCenterProperties;
import com.huang.demo.file.service.FileSecurityScanner;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RuleBasedFileSecurityScanner implements FileSecurityScanner {

    private static final int MAX_HEADER_BYTES = 16;
    private static final Set<String> DANGEROUS_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "exe", "dll", "com", "bat", "cmd", "scr", "msi", "ps1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "lnk", "jar", "class", "sh"));
    private static final Set<String> ZIP_BASED_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "zip", "docx", "xlsx", "pptx", "jar"));
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "txt", "csv", "json", "xml", "md", "log", "properties", "yaml", "yml"));

    private final FileCenterProperties properties;

    public RuleBasedFileSecurityScanner(FileCenterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void validateMetadata(String originalName, String contentType) {
        String normalizedName = normalizeOriginalName(originalName);
        String fileExt = resolveFileExt(normalizedName);
        if (fileExt.isEmpty()) {
            throw new IllegalArgumentException("文件类型不被允许，originalName=" + normalizedName);
        }
        if (isDangerousExtension(fileExt)) {
            throw new IllegalArgumentException("文件类型不被允许，extension=" + fileExt);
        }
        if (!isAllowedExtension(fileExt)) {
            throw new IllegalArgumentException("文件类型不被允许，extension=" + fileExt);
        }
        if (hasText(contentType) && !isAllowedContentType(contentType)) {
            throw new IllegalArgumentException("文件 Content-Type 不被允许，contentType=" + contentType);
        }
    }

    @Override
    public void scan(InputStream inputStream, String originalName, String contentType, long fileSize) {
        validateMetadata(originalName, contentType);
        if (!properties.isSecurityScanEnabled()) {
            return;
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (fileSize <= 0L) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        byte[] header = readHeader(inputStream);
        String fileExt = resolveFileExt(normalizeOriginalName(originalName));
        if (isExecutableHeader(header)) {
            throw new IllegalArgumentException("文件内容疑似可执行程序，originalName=" + normalizeOriginalName(originalName));
        }
        if (isClassHeader(header)) {
            throw new IllegalArgumentException("文件内容疑似 Java 字节码，originalName=" + normalizeOriginalName(originalName));
        }
        if (isElfHeader(header)) {
            throw new IllegalArgumentException("文件内容疑似 Linux 可执行程序，originalName=" + normalizeOriginalName(originalName));
        }
        if ("pdf".equals(fileExt) && !isPdfHeader(header)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if ("png".equals(fileExt) && !isPngHeader(header)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if (("jpg".equals(fileExt) || "jpeg".equals(fileExt)) && !isJpegHeader(header)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if ("gif".equals(fileExt) && !isGifHeader(header)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if (isZipBasedExtension(fileExt) && !isZipHeader(header)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if (isZipHeader(header) && !isZipBasedExtension(fileExt) && !isPlainTextExtension(fileExt) && !"bin".equals(fileExt)) {
            throw new IllegalArgumentException("文件内容与后缀不匹配，originalName=" + normalizeOriginalName(originalName));
        }
        if (PLAIN_TEXT_EXTENSIONS.contains(fileExt) && isBinaryLike(header)) {
            throw new IllegalArgumentException("文件内容疑似二进制文件，originalName=" + normalizeOriginalName(originalName));
        }
    }

    private boolean isAllowedExtension(String fileExt) {
        List<String> allowedExtensions = properties.getAllowedUploadExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return true;
        }
        for (String allowedExtension : allowedExtensions) {
            if (fileExt.equalsIgnoreCase(trimToEmpty(allowedExtension))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedContentType(String contentType) {
        String normalizedContentType = contentType.trim().toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(normalizedContentType)) {
            return true;
        }
        List<String> allowedMimeTypes = properties.getAllowedUploadMimeTypes();
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
            return true;
        }
        for (String allowedMimeType : allowedMimeTypes) {
            if (normalizedContentType.equals(trimToEmpty(allowedMimeType).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isDangerousExtension(String fileExt) {
        return DANGEROUS_EXTENSIONS.contains(fileExt);
    }

    private boolean isZipBasedExtension(String fileExt) {
        return ZIP_BASED_EXTENSIONS.contains(fileExt) || "docx".equals(fileExt) || "xlsx".equals(fileExt) || "pptx".equals(fileExt);
    }

    private boolean isPlainTextExtension(String fileExt) {
        return PLAIN_TEXT_EXTENSIONS.contains(fileExt);
    }

    private byte[] readHeader(InputStream inputStream) {
        try {
            InputStream safeStream = inputStream.markSupported() ? inputStream : new BufferedInputStream(inputStream);
            if (safeStream.markSupported()) {
                safeStream.mark(MAX_HEADER_BYTES);
            }
            byte[] header = new byte[MAX_HEADER_BYTES];
            int read = 0;
            while (read < header.length) {
                int count = safeStream.read(header, read, header.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (safeStream.markSupported()) {
                safeStream.reset();
            }
            if (read == header.length) {
                return header;
            }
            return Arrays.copyOf(header, read);
        } catch (IOException ex) {
            throw new IllegalStateException("读取文件头失败", ex);
        }
    }

    private boolean isExecutableHeader(byte[] header) {
        return startsWith(header, new byte[]{'M', 'Z'});
    }

    private boolean isElfHeader(byte[] header) {
        return startsWith(header, new byte[]{0x7f, 'E', 'L', 'F'});
    }

    private boolean isClassHeader(byte[] header) {
        return startsWith(header, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
    }

    private boolean isPdfHeader(byte[] header) {
        return startsWith(header, new byte[]{'%', 'P', 'D', 'F', '-'});
    }

    private boolean isPngHeader(byte[] header) {
        return startsWith(header, new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
        });
    }

    private boolean isJpegHeader(byte[] header) {
        return startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private boolean isGifHeader(byte[] header) {
        return startsWith(header, new byte[]{'G', 'I', 'F', '8'});
    }

    private boolean isZipHeader(byte[] header) {
        return startsWith(header, new byte[]{'P', 'K'});
    }

    private boolean isBinaryLike(byte[] header) {
        if (header == null || header.length == 0) {
            return true;
        }
        int printableCount = 0;
        for (byte value : header) {
            int unsigned = value & 0xff;
            if (unsigned == 0x09 || unsigned == 0x0A || unsigned == 0x0D
                    || (unsigned >= 0x20 && unsigned <= 0x7E)) {
                printableCount++;
            }
        }
        return printableCount * 2 < header.length;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || prefix == null || value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String normalizeOriginalName(String originalName) {
        if (!hasText(originalName)) {
            return "unknown";
        }
        String normalized = originalName.trim().replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replace("\u0000", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private String resolveFileExt(String originalName) {
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalName.length() - 1) {
            return "";
        }
        return trimToEmpty(originalName.substring(dotIndex + 1)).toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
