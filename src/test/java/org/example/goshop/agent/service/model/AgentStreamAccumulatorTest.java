package org.example.goshop.agent.service.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型流正文和指标聚合测试。
 */
class AgentStreamAccumulatorTest {

    @Test
    void shouldCollectUsageFromContentlessTerminalChunk() {
        AgentStreamAccumulator accumulator =
                new AgentStreamAccumulator();

        accumulator.accept(
                AgentModelStreamChunk.textOnly("您")
        );
        accumulator.accept(
                AgentModelStreamChunk.textOnly("好")
        );
        accumulator.accept(
                new AgentModelStreamChunk(
                        "",
                        105,
                        9
                )
        );

        AgentRunMetrics metrics = accumulator.metrics();

        assertEquals("您好", accumulator.content());
        assertEquals(105, metrics.promptTokens());
        assertEquals(9, metrics.completionTokens());
        assertNotNull(metrics.firstTokenMs());
        assertTrue(metrics.firstTokenMs() >= 0);
        assertTrue(metrics.durationMs() >= 0);
    }

    @Test
    void shouldUseLatestUsageSnapshotInsteadOfAddingChunks() {
        AgentStreamAccumulator accumulator =
                new AgentStreamAccumulator();

        /*
         * 一些兼容供应商会重复发送累计 Usage。若按分片相加，这里会被
         * 错误记录成 prompt=230、completion=35。
         */
        accumulator.accept(
                new AgentModelStreamChunk(
                        "回答",
                        "chatcmpl-1",
                        110,
                        15
                )
        );
        accumulator.accept(
                new AgentModelStreamChunk(
                        "完成",
                        "chatcmpl-1",
                        120,
                        20
                )
        );

        AgentRunMetrics metrics = accumulator.metrics();

        assertEquals("回答完成", accumulator.content());
        assertEquals(120, metrics.promptTokens());
        assertEquals(20, metrics.completionTokens());
    }

    @Test
    void shouldAddUsageAcrossToolCallingModelRounds() {
        AgentStreamAccumulator accumulator =
                new AgentStreamAccumulator();

        /* 第一次模型请求选择工具，第二次请求结合工具结果生成回答。 */
        accumulator.accept(
                new AgentModelStreamChunk(
                        "",
                        "chatcmpl-tool-call",
                        80,
                        6
                )
        );
        accumulator.accept(
                new AgentModelStreamChunk(
                        "查询完成",
                        "chatcmpl-final-answer",
                        130,
                        16
                )
        );

        AgentRunMetrics metrics = accumulator.metrics();

        assertEquals(210, metrics.promptTokens());
        assertEquals(22, metrics.completionTokens());
    }

    @Test
    void shouldLeaveTokensEmptyForDeterministicText() {
        AgentStreamAccumulator accumulator =
                new AgentStreamAccumulator();

        accumulator.accept(
                AgentModelStreamChunk.textOnly(
                        "已准备待确认加购信息"
                )
        );

        AgentRunMetrics metrics = accumulator.metrics();

        assertNull(metrics.promptTokens());
        assertNull(metrics.completionTokens());
    }
}
