package com.huang.demo.excel.service.impl;

import com.huang.demo.common.api.dto.CursorPageResponse;
import com.huang.demo.excel.api.dto.StudentCursorQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageResponse;
import com.huang.demo.excel.api.dto.StudentResponse;
import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.service.StudentQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StudentQueryServiceImpl implements StudentQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CURSOR_PAGE_SIZE = 1000;

    private final StudentMapper studentMapper;

    public StudentQueryServiceImpl(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    @Override
    public StudentPageResponse page(StudentPageQueryRequest request) {
        StudentPageQueryRequest safeRequest = request == null ? new StudentPageQueryRequest() : request;
        normalizePageQuery(safeRequest);
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize(), MAX_PAGE_SIZE);
        int offset = (pageNo - 1) * pageSize;
        long total = studentMapper.countByPageQuery(safeRequest);
        List<StudentExportRecord> records = studentMapper.listByPageQuery(safeRequest, offset, pageSize);
        return StudentPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(toResponses(records))
                .build();
    }

    @Override
    public CursorPageResponse<StudentResponse> cursorPage(StudentCursorQueryRequest request) {
        StudentCursorQueryRequest safeRequest = request == null ? new StudentCursorQueryRequest() : request;
        normalizeCursorQuery(safeRequest);
        long lastId = safeRequest.getCursor() == null ? 0L : Math.max(0L, safeRequest.getCursor());
        int pageSize = normalizePageSize(safeRequest.getPageSize(), MAX_CURSOR_PAGE_SIZE);
        List<StudentExportRecord> records = studentMapper.listByCursorQuery(safeRequest, lastId, pageSize + 1);
        boolean hasMore = records.size() > pageSize;
        if (hasMore) {
            records = new ArrayList<StudentExportRecord>(records.subList(0, pageSize));
        }
        Long nextCursor = records.isEmpty() ? lastId : records.get(records.size() - 1).getId();
        return CursorPageResponse.<StudentResponse>builder()
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .pageSize(pageSize)
                .records(toResponses(records))
                .build();
    }

    private void normalizePageQuery(StudentPageQueryRequest request) {
        request.setStudentNo(normalizeOptionalText(request.getStudentNo(), 32));
        request.setNameKeyword(normalizeOptionalText(request.getNameKeyword(), 64));
        request.setClassName(normalizeOptionalText(request.getClassName(), 64));
        request.setGender(normalizeOptionalText(request.getGender(), 16));
        request.setBirthdayFrom(normalizeOptionalText(request.getBirthdayFrom(), 32));
        request.setBirthdayTo(normalizeOptionalText(request.getBirthdayTo(), 32));
        validateAgeRange(request.getMinAge(), request.getMaxAge());
        validateBirthdayRange(request.getBirthdayFrom(), request.getBirthdayTo());
    }

    private void normalizeCursorQuery(StudentCursorQueryRequest request) {
        request.setStudentNo(normalizeOptionalText(request.getStudentNo(), 32));
        request.setNameKeyword(normalizeOptionalText(request.getNameKeyword(), 64));
        request.setClassName(normalizeOptionalText(request.getClassName(), 64));
        request.setGender(normalizeOptionalText(request.getGender(), 16));
        request.setBirthdayFrom(normalizeOptionalText(request.getBirthdayFrom(), 32));
        request.setBirthdayTo(normalizeOptionalText(request.getBirthdayTo(), 32));
        validateAgeRange(request.getMinAge(), request.getMaxAge());
        validateBirthdayRange(request.getBirthdayFrom(), request.getBirthdayTo());
    }

    private List<StudentResponse> toResponses(List<StudentExportRecord> records) {
        List<StudentResponse> responses = new ArrayList<StudentResponse>(records.size());
        for (StudentExportRecord record : records) {
            responses.add(StudentResponse.from(record));
        }
        return responses;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private void validateAgeRange(Integer minAge, Integer maxAge) {
        validateAge(minAge, "最小年龄");
        validateAge(maxAge, "最大年龄");
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new IllegalArgumentException("最小年龄不能大于最大年龄");
        }
    }

    private void validateAge(Integer age, String fieldName) {
        if (age != null && (age < 0 || age > 150)) {
            throw new IllegalArgumentException(fieldName + "必须在0到150之间");
        }
    }

    private void validateBirthdayRange(String birthdayFrom, String birthdayTo) {
        if (birthdayFrom != null && birthdayTo != null && birthdayTo.compareTo(birthdayFrom) < 0) {
            throw new IllegalArgumentException("生日范围不正确");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    private int normalizePageSize(Integer pageSize, int maxPageSize) {
        if (pageSize == null || pageSize < 1) {
            return Math.min(DEFAULT_PAGE_SIZE, maxPageSize);
        }
        return Math.min(pageSize, maxPageSize);
    }
}
