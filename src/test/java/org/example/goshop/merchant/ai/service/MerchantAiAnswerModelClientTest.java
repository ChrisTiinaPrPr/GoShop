package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeMatchResponse;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.product.dto.ProductListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 模型 Prompt 的证据编号、实时价格和关闭降级测试。 */
class MerchantAiAnswerModelClientTest {

    @Test
    void shouldBuildSeparatedKnowledgeAndCurrentProductPrompt() {
        MerchantAiAnswerModelClient client = new MerchantAiAnswerModelClient(
                mockProvider()
        );
        Merchant merchant = merchant();
        MerchantAiAssistant assistant = assistant();

        String prompt = client.buildUserPrompt(
                merchant,
                assistant,
                "M7现在多少钱",
                List.of(new MerchantAiKnowledgeMatchResponse(
                        9001L,
                        "guide.md",
                        2,
                        "M7 文档参考价为 188 元。",
                        0.70D
                )),
                List.of(new ProductListResponse(
                        9201L,
                        7001L,
                        3001L,
                        "M7 鼠标",
                        null,
                        19900L,
                        20L
                )),
                25
        );

        assertTrue(prompt.contains("[资料1]"));
        assertTrue(prompt.contains("当前最低价=199.00元"));
        assertTrue(prompt.contains("快照只包含部分公开商品"));
        assertTrue(prompt.contains("<buyer_question>"));
    }

    @Test
    void shouldFailSafelyWhenChatModelIsDisabled() {
        MerchantAiAnswerModelClient client = new MerchantAiAnswerModelClient(
                mockProvider()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> client.stream(
                        merchant(),
                        assistant(),
                        "M7怎么样",
                        List.of(),
                        List.of(),
                        0
                )
        );

        assertEquals(50301, exception.getCode());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient> mockProvider() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setId(7001L);
        merchant.setName("星环外设馆");
        return merchant;
    }

    private MerchantAiAssistant assistant() {
        MerchantAiAssistant assistant = new MerchantAiAssistant();
        assistant.setId(8001L);
        assistant.setName("星环导购");
        return assistant;
    }
}
