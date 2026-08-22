package com.huang.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "server.port=0",
        "spring.datasource.url=jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.redis.host=test-redis.invalid",
        "spring.redis.port=0",
        "spring.redis.database=0",
        "spring.redis.password=",
        "app.security.init-enabled=false",
        "app.excel.init-enabled=false",
        "app.minio.endpoint=http://test-minio.invalid",
        "app.minio.access-key=test",
        "app.minio.secret-key=test",
        "app.minio.bucket-name=student-excel",
        "app.minio.lifecycle-enabled=false",
        "app.task.init-enabled=false",
        "app.file.init-enabled=false"
})
class DemoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void fileCenterCorsAllowsLocalFileOrigin() throws Exception {
        mockMvc.perform(options("/api/files/instant-check")
                        .header("Origin", "null")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "null"));
    }

    @Test
    void seedPathVariableTypeMismatchReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/excel/seed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCEL_PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数类型错误"));
    }

    @Test
    void excelImportMissingFileReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/excel/import")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCEL_PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value("缺少请求文件参数: file"));
    }

    @Test
    void fileUploadMissingFileReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/files/upload")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value("缺少请求文件参数: file"));
    }

    @Test
    void excelImportUnsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/excel/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("EXCEL_PARAM_ERROR"))
                .andExpect(jsonPath("$.message").value("请求 Content-Type 不支持"));
    }

}
