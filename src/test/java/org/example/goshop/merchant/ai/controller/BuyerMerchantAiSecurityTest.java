package org.example.goshop.merchant.ai.controller;

import org.example.goshop.merchant.ai.service.BuyerMerchantAiAnswerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 防止公开店铺通配规则意外放行消耗模型资源的提问接口。 */
@SpringBootTest(properties = {
        // 安全路由测试只需要 Spring MVC，不访问模型供应商或开发者本地密钥。
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
@AutoConfigureMockMvc
class BuyerMerchantAiSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private BuyerMerchantAiAnswerService answerService;

    @Test
    void shouldRequireBuyerAuthenticationBeforeController() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/buyer/merchants/7001/ai-assistant/questions"
                )
                        .contentType("application/json")
                        .content("{\"question\":\"M7鼠标怎么样\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }
}
