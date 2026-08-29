package org.example.goshop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        // 测试上下文不访问外部模型供应商，也不依赖开发者本地 .env。
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.vectorstore.type=none",
        "goshop.jwt.secret=goshop-unit-test-only-jwt-secret-at-least-32-bytes",
        "goshop.jwt.access-token-minutes=120"
})
class GoShopApplicationTests {

    @Test
    void contextLoads() {
    }

}
