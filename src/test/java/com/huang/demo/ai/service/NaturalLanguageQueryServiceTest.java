package com.huang.demo.ai.service;

import com.huang.demo.ai.config.AiQueryProperties;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class NaturalLanguageQueryServiceTest {

    @Test
    void parseWhitelistedDropsUnknownFieldsAndClampsAges() throws Exception {
        AiQueryProperties properties = new AiQueryProperties();
        NaturalLanguageQueryService service = new NaturalLanguageQueryService(properties);
        Method method = NaturalLanguageQueryService.class
                .getDeclaredMethod("parseWhitelisted", String.class);
        method.setAccessible(true);
        String json = "{\"studentNo\":\"S001\",\"evilSql\":\"DROP TABLE\","
                + "\"minAge\":30,\"maxAge\":20,\"minAge2\":999,\"gender\":\"男\"}";
        StudentPageQueryRequest request = (StudentPageQueryRequest) method.invoke(service, json);
        assertEquals("S001", request.getStudentNo());
        assertEquals("男", request.getGender());
        // 未知字段丢弃 + 年龄区间自动交换
        assertEquals(Integer.valueOf(20), request.getMinAge());
        assertEquals(Integer.valueOf(30), request.getMaxAge());
        assertNull(request.getNameKeyword());
    }

    @Test
    void parseReturnsNullWhenDisabled() {
        NaturalLanguageQueryService service = new NaturalLanguageQueryService(new AiQueryProperties());
        assertFalse(service.isEnabled());
        assertNull(service.parse("一班20岁以下的学生"));
    }

    @Test
    void extractJsonStripsMarkdownFence() throws Exception {
        AiQueryProperties properties = new AiQueryProperties();
        NaturalLanguageQueryService service = new NaturalLanguageQueryService(properties);
        Method method = NaturalLanguageQueryService.class
                .getDeclaredMethod("extractJson", String.class);
        method.setAccessible(true);
        String fenced = "好的，结果如下：\n```json\n{\"className\":\"一班\"}\n```";
        assertEquals("{\"className\":\"一班\"}", method.invoke(service, fenced));
    }
}
