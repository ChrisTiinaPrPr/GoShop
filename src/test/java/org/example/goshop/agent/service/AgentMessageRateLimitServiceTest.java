package org.example.goshop.agent.service;

import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Agent Redis 消息限流的单元测试。
 *
 * <p>测试不连接真实 Redis，而是验证脚本结果到业务行为的映射，以及
 * 用户维度 key、会话级幂等 member 和配置阈值是否正确传给 Redis。</p>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class AgentMessageRateLimitServiceTest {

    private static final String CLIENT_MESSAGE_ID =
            "7d750805-8e76-4e9c-8480-35ec9fe42a74";

    private StringRedisTemplate redisTemplate;
    private AgentMessageRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        rateLimitService = new AgentMessageRateLimitService(
                redisTemplate,
                enabledProperties()
        );
    }

    @Test
    void shouldAllowRequestWhenLuaScriptReturnsOne() {
        stubScriptResult(1L);

        assertDoesNotThrow(() ->
                rateLimitService.checkAllowed(
                        11L,
                        22L,
                        CLIENT_MESSAGE_ID
                )
        );

        /*
         * key 只能使用服务端 userId；member 同时包含 conversationId 和
         * clientMessageId，避免不同会话偶然复用同一 UUID 时互相覆盖。
         */
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "agent:rate-limit:message:v1:11"
                )),
                eq("22:" + CLIENT_MESSAGE_ID),
                eq("10")
        );
    }

    @Test
    void shouldRejectNewMessageWhenSlidingWindowIsFull() {
        stubScriptResult(0L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.checkAllowed(
                        11L,
                        22L,
                        CLIENT_MESSAGE_ID
                )
        );

        assertEquals(42901, exception.getCode());
        assertEquals(
                "购物助手请求过于频繁，请稍后再试",
                exception.getMessage()
        );
    }

    @Test
    void shouldFailOpenWhenRedisIsTemporarilyUnavailable() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisTemplate)
                .execute(
                        any(DefaultRedisScript.class),
                        anyList(),
                        any(),
                        any()
                );

        assertDoesNotThrow(() ->
                rateLimitService.checkAllowed(
                        11L,
                        22L,
                        CLIENT_MESSAGE_ID
                )
        );
    }

    @Test
    void shouldFailOpenWhenRedisReturnsNoScriptResult() {
        stubScriptResult(null);

        assertDoesNotThrow(() ->
                rateLimitService.checkAllowed(
                        11L,
                        22L,
                        CLIENT_MESSAGE_ID
                )
        );
    }

    private void stubScriptResult(Long result) {
        doReturn(result)
                .when(redisTemplate)
                .execute(
                        any(DefaultRedisScript.class),
                        anyList(),
                        any(),
                        any()
                );
    }

    private AgentProperties enabledProperties() {
        return new AgentProperties(
                true,
                10,
                10,
                8,
                45,
                10,
                1000,
                1200
        );
    }
}
