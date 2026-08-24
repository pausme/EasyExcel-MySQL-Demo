package com.huang.demo.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huang.demo.ai.config.AiQueryProperties;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 自然语言 → 学生查询条件。
 *
 * 设计约束：
 * - 模型只输出 JSON 过滤条件，不生成 SQL——杜绝注入面；
 * - 输出字段白名单校验（studentNo/nameKeyword/className/gender/minAge/maxAge），
 *   模型幻觉出的其它字段直接丢弃；
 * - AI 不可用/未配置/解析失败时返回 null，由 Controller 走明确的降级提示。
 */
@Service
public class NaturalLanguageQueryService {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageQueryService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SYSTEM_PROMPT =
            "你是学生数据查询条件解析器。把用户的自然语言转换为 JSON 对象，只允许这些字段："
                    + "studentNo(精确学号,string)、nameKeyword(姓名关键字,string)、"
                    + "className(班级,string)、gender(性别,string,男/女)、"
                    + "minAge(int)、maxAge(int)。"
                    + "无法确定的条件不要输出该字段。只输出 JSON 本身，不要任何解释或 markdown 代码块。";

    private final AiQueryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client;

    public NaturalLanguageQueryService(AiQueryProperties properties) {
        this.properties = properties;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(Math.max(5, properties.getTimeoutSeconds()), TimeUnit.SECONDS)
                .build();
    }

    public boolean isEnabled() {
        return properties.isEnabled()
                && properties.getChatEndpoint() != null && !properties.getChatEndpoint().trim().isEmpty();
    }

    /**
     * @return 解析出的查询条件；AI 未启用/调用失败/输出非法时返回 null（调用方降级）
     */
    public StudentPageQueryRequest parse(String naturalLanguage) {
        if (!isEnabled() || naturalLanguage == null || naturalLanguage.trim().isEmpty()) {
            return null;
        }
        try {
            String content = chat(naturalLanguage.trim());
            return parseWhitelisted(extractJson(content));
        } catch (RuntimeException ex) {
            log.warn("natural language query parse failed, input={}", naturalLanguage, ex);
            return null;
        }
    }

    private String chat(String userText) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);

        Request.Builder rb = new Request.Builder()
                .url(properties.getChatEndpoint().trim())
                .post(RequestBody.create(body.toString(), JSON));
        if (properties.getApiKey() != null && !properties.getApiKey().trim().isEmpty()) {
            rb.header("Authorization", "Bearer " + properties.getApiKey().trim());
        }
        try (Response response = client.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("AI 接口返回异常, http=" + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().trim().isEmpty()) {
                throw new IllegalStateException("AI 接口未返回内容");
            }
            return content.asText();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("AI 接口调用失败", ex);
        }
    }

    /** 模型偶尔会带 ```json 包裹或前后废话——截取首个 { 到末个 } 之间 */
    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("AI 输出中不含 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    private StudentPageQueryRequest parseWhitelisted(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            StudentPageQueryRequest request = new StudentPageQueryRequest();
            request.setStudentNo(textOrNull(node, "studentNo", 32));
            request.setNameKeyword(textOrNull(node, "nameKeyword", 64));
            request.setClassName(textOrNull(node, "className", 64));
            request.setGender(textOrNull(node, "gender", 16));
            Integer minAge = intOrNull(node, "minAge");
            Integer maxAge = intOrNull(node, "maxAge");
            if (minAge != null && (minAge < 0 || minAge > 150)) {
                minAge = null;
            }
            if (maxAge != null && (maxAge < 0 || maxAge > 150)) {
                maxAge = null;
            }
            if (minAge != null && maxAge != null && minAge > maxAge) {
                Integer tmp = minAge;
                minAge = maxAge;
                maxAge = tmp;
            }
            request.setMinAge(minAge);
            request.setMaxAge(maxAge);
            return request;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("AI 输出 JSON 解析失败", ex);
        }
    }

    private String textOrNull(JsonNode node, String field, int maxLen) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String text = v.asText().trim();
        if (text.isEmpty() || text.length() > maxLen) {
            return null;
        }
        return text;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isIntegralNumber()) {
            return null;
        }
        return v.asInt();
    }
}
