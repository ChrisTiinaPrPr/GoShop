package org.example.goshop.common.exception;

import org.example.goshop.common.api.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实嵌入式 Tomcat 验证文件超过 multipart 限制时的响应，
 * 防止上传异常重新落入全局 50000 兜底。
 */
@SpringBootTest(
        classes = MultipartUploadExceptionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=5MB",
                "spring.servlet.multipart.max-request-size=6MB",
                "spring.servlet.multipart.resolve-lazily=true",
                "server.tomcat.max-http-form-post-size=-1",
                "server.tomcat.max-swallow-size=-1",
                // 此测试只验证 Tomcat multipart 边界，不应初始化任何外部 AI 客户端。
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.model.audio.speech=none",
                "spring.ai.model.audio.transcription=none",
                "spring.ai.model.image=none",
                "spring.ai.model.moderation=none",
                "spring.ai.vectorstore.type=none"
        }
)
class MultipartUploadExceptionIntegrationTest {

    /**
     * 直接在测试内构造超过 5 MB 的匿名载荷，避免为了边界测试把个人截图或
     * 其他真实图片作为固定资源提交到公开仓库。
     */
    private static final byte[] LARGE_AVATAR = new byte[5 * 1024 * 1024 + 1];

    @LocalServerPort
    private int port;

    @Test
    void shouldReturn41301WhenUploadExceedsFiveMegabytes() throws Exception {
        assertTrue(LARGE_AVATAR.length > 5L * 1024 * 1024,
                "测试图片必须大于 5MB");

        String boundary = "----GoShopUploadBoundary";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/test/avatar"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(
                        multipartBody(boundary, LARGE_AVATAR)
                ))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"code\":40001"));
        assertTrue(response.body().contains("上传文件不能超过 5MB"));
    }

    private byte[] multipartBody(String boundary, byte[] fileContent) throws IOException {
        try (ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.png\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(fileContent);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        }
    }

    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataRedisAutoConfiguration.class
    })
    @Import({
            GlobalExceptionHandler.class,
            MultipartUploadExceptionFilter.class,
            TestUploadController.class,
            TestSecurityConfiguration.class
    })
    static class TestApplication {
    }

    @RestController
    static class TestUploadController {

        @PatchMapping(value = "/test/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Result<Void> upload(@RequestParam MultipartFile avatar) {
            return Result.ok();
        }
    }

    /**
     * 保持 CSRF 开启以复现过滤器链读取 multipart 参数的场景。
     * 旧实现会在此处被 Tomcat 异常打断，无法进入全局异常处理器。
     */
    static class TestSecurityConfiguration {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }
}
