package com.huang.demo.common.web;

import com.huang.demo.common.api.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    @Test
    void methodArgumentNotValidReturnsFieldErrors() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "pageSize", 500,
                false, null, null, "每页条数不能超过100"));
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", Object.class);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0), bindingResult);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks/page");
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentNotValid(exception, request);

        assertEquals(400, response.getStatusCodeValue());
        assertEquals("TASK_PARAM_ERROR", response.getBody().getCode());
        assertEquals("请求参数校验失败", response.getBody().getMessage());
        assertFalse(response.getBody().getFieldErrors().isEmpty());
        assertEquals("pageSize", response.getBody().getFieldErrors().get(0).getField());
    }

    @SuppressWarnings("unused")
    private void sample(Object request) {
    }
}
