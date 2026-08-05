package com.huang.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
        "app.excel.init-enabled=false",
        "app.minio.endpoint=http://test-minio.invalid",
        "app.minio.access-key=test",
        "app.minio.secret-key=test",
        "app.minio.bucket-name=public",
        "app.minio.lifecycle-enabled=false",
        "app.file.init-enabled=false"
})
class DemoApplicationTests {

    @Test
    void contextLoads() {
    }

}
