package org.example.goshop.agent.service;

import org.example.goshop.agent.service.model.AgentModelStreamChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型流响应映射测试。
 *
 * <p>不连接真实模型，只使用 Spring AI 的响应对象验证正文和 Usage
 * 元数据能够同时进入业务分片。</p>
 */
class AgentModelStreamingClientTest {

    @Test
    void shouldMapContentAndProviderUsage() {
        ChatResponse response = response(
                "您好",
                new DefaultUsage(120, 18)
        );

        AgentModelStreamChunk chunk =
                AgentModelStreamingClient
                        .toStreamChunk(response);

        assertEquals("您好", chunk.contentDelta());
        assertEquals("chatcmpl-test", chunk.responseId());
        assertEquals(120, chunk.promptTokens());
        assertEquals(18, chunk.completionTokens());
        assertTrue(chunk.hasContent());
        assertTrue(chunk.hasUsage());
    }

    @Test
    void shouldKeepUsageOnlyTerminalChunk() {
        ChatResponse response = response(
                "",
                new DefaultUsage(86, 12)
        );

        AgentModelStreamChunk chunk =
                AgentModelStreamingClient
                        .toStreamChunk(response);

        assertFalse(chunk.hasContent());
        assertTrue(chunk.hasUsage());
        assertEquals(86, chunk.promptTokens());
        assertEquals(12, chunk.completionTokens());
    }

    @Test
    void shouldTreatEmptyUsageAsUnavailable() {
        ChatResponse response = response(
                "完成",
                new DefaultUsage(0, 0)
        );

        AgentModelStreamChunk chunk =
                AgentModelStreamingClient
                        .toStreamChunk(response);

        assertEquals("完成", chunk.contentDelta());
        assertNull(chunk.promptTokens());
        assertNull(chunk.completionTokens());
        assertFalse(chunk.hasUsage());
    }

    /**
     * 创建带单个文本 Generation 的 Spring AI 响应。
     */
    private ChatResponse response(
            String content,
            DefaultUsage usage
    ) {
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder()
                        .id("chatcmpl-test")
                        .usage(usage)
                        .build();

        return new ChatResponse(
                List.of(
                        new Generation(
                                new AssistantMessage(content)
                        )
                ),
                metadata
        );
    }
}
