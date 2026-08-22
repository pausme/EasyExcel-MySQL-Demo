package com.huang.demo.excel.service.impl;

import com.huang.demo.common.api.dto.CursorPageResponse;
import com.huang.demo.excel.api.dto.StudentCursorQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageResponse;
import com.huang.demo.excel.api.dto.StudentResponse;
import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.repository.StudentMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentQueryServiceImplTest {

    @Test
    void pageNormalizesBoundsAndReturnsStudents() {
        StudentMapper mapper = mock(StudentMapper.class);
        when(mapper.countByPageQuery(any(StudentPageQueryRequest.class))).thenReturn(1L);
        when(mapper.listByPageQuery(any(StudentPageQueryRequest.class), eq(0), eq(100)))
                .thenReturn(Collections.singletonList(student(1L, "S001")));
        StudentQueryServiceImpl service = new StudentQueryServiceImpl(mapper);
        StudentPageQueryRequest request = new StudentPageQueryRequest();
        request.setPageNo(0);
        request.setPageSize(500);
        request.setStudentNo(" S001 ");
        request.setNameKeyword(" 张 ");

        StudentPageResponse response = service.page(request);

        assertEquals(1L, response.getTotal());
        assertEquals(1, response.getPageNo());
        assertEquals(100, response.getPageSize());
        assertEquals("S001", response.getRecords().get(0).getStudentNo());
        verify(mapper).listByPageQuery(any(StudentPageQueryRequest.class), eq(0), eq(100));
    }

    @Test
    void cursorPageFetchesOneExtraRowAndBuildsNextCursor() {
        StudentMapper mapper = mock(StudentMapper.class);
        when(mapper.listByCursorQuery(any(StudentCursorQueryRequest.class), eq(10L), eq(3)))
                .thenReturn(Arrays.asList(student(11L, "S011"), student(12L, "S012"), student(13L, "S013")));
        StudentQueryServiceImpl service = new StudentQueryServiceImpl(mapper);
        StudentCursorQueryRequest request = new StudentCursorQueryRequest();
        request.setCursor(10L);
        request.setPageSize(2);

        CursorPageResponse<StudentResponse> response = service.cursorPage(request);

        assertTrue(response.isHasMore());
        assertEquals(12L, response.getNextCursor().longValue());
        assertEquals(2, response.getRecords().size());
        verify(mapper).listByCursorQuery(any(StudentCursorQueryRequest.class), eq(10L), eq(3));
    }

    @Test
    void cursorPageReturnsLastCursorWhenNoRows() {
        StudentMapper mapper = mock(StudentMapper.class);
        when(mapper.listByCursorQuery(any(StudentCursorQueryRequest.class), eq(20L), eq(21)))
                .thenReturn(Collections.emptyList());
        StudentQueryServiceImpl service = new StudentQueryServiceImpl(mapper);
        StudentCursorQueryRequest request = new StudentCursorQueryRequest();
        request.setCursor(20L);

        CursorPageResponse<StudentResponse> response = service.cursorPage(request);

        assertFalse(response.isHasMore());
        assertEquals(20L, response.getNextCursor().longValue());
        assertEquals(0, response.getRecords().size());
    }

    @Test
    void pageRejectsInvalidAgeRange() {
        StudentQueryServiceImpl service = new StudentQueryServiceImpl(mock(StudentMapper.class));
        StudentPageQueryRequest request = new StudentPageQueryRequest();
        request.setMinAge(80);
        request.setMaxAge(18);

        assertThrows(IllegalArgumentException.class, () -> service.page(request));
    }

    private StudentExportRecord student(Long id, String studentNo) {
        return StudentExportRecord.builder()
                .id(id)
                .studentNo(studentNo)
                .name("张三")
                .age(18)
                .gender("男")
                .className("一班")
                .email(studentNo + "@example.com")
                .birthday("2008-01-01")
                .build();
    }
}
