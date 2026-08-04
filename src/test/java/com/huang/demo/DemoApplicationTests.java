package com.huang.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.redis.host=127.0.0.1",
        "spring.redis.port=6379",
        "spring.redis.database=0",
        "spring.redis.password=",
        "app.excel.init-enabled=false",
        "app.minio.endpoint=http://127.0.0.1:9000",
        "app.minio.access-key=test",
        "app.minio.secret-key=test",
        "app.minio.bucket-name=public"
})
class DemoApplicationTests {

    @Test
    void contextLoads() {
    }

}
