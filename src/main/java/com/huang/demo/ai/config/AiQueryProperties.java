package com.huang.demo.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiQueryProperties {

    /**
     * 总开关：未配置 api-key 时 NL 查询接口返回明确提示而不是报错。
     */
    private boolean enabled = false;

    /**
     * OpenAI 兼容 Chat Completions 端点（含 /chat/completions），例如
     * http://localhost:11434/v1/chat/completions 或任意兼容网关。
     */
    private String chatEndpoint = "";

    private String apiKey = "";

    private String model = "gpt-4o-mini";

    /**
     * 单次请求超时秒数，超时按解析失败处理。
     */
    private int timeoutSeconds = 15;
}
