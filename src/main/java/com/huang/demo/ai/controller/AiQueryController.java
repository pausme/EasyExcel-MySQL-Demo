package com.huang.demo.ai.controller;

import com.huang.demo.ai.service.NaturalLanguageQueryService;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageResponse;
import com.huang.demo.excel.service.StudentQueryService;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiQueryController {

    private final NaturalLanguageQueryService naturalLanguageQueryService;
    private final StudentQueryService studentQueryService;

    public AiQueryController(NaturalLanguageQueryService naturalLanguageQueryService,
                             StudentQueryService studentQueryService) {
        this.naturalLanguageQueryService = naturalLanguageQueryService;
        this.studentQueryService = studentQueryService;
    }

    @ApiOperation("自然语言查询学生（NL → 结构化过滤条件 → 学生分页）")
    @PostMapping("/students/query")
    public Map<String, Object> query(@RequestBody(required = false) NaturalQueryRequest request) {
        String text = request == null ? null : request.getQuery();
        if (text == null || text.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询语句不能为空");
        }
        if (!naturalLanguageQueryService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 查询未启用，请配置 APP_AI_ENABLED 与 APP_AI_CHAT_ENDPOINT");
        }
        StudentPageQueryRequest parsed = naturalLanguageQueryService.parse(text);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI 解析查询语句失败，请换一种表述或稍后重试");
        }
        StudentPageResponse page = studentQueryService.page(parsed);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("parsedFilters", parsed);
        result.put("page", page);
        return result;
    }

    public static class NaturalQueryRequest {
        private String query;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }
}
