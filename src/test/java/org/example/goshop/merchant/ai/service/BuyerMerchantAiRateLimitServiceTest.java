package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 智能导购模型资源限流与 Redis 故障降级测试。 */
@ExtendWith(MockitoExtension.class)
class BuyerMerchantAiRateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @InjectMocks
    private BuyerMerchantAiRateLimitService rateLimitService;

    @Test
    void shouldRejectWhenSlidingWindowQuotaIsExhausted() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(),
                eq("10")
        )).thenReturn(0L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.checkAllowed(1001L, 7001L)
        );

        assertEquals(42901, exception.getCode());
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(
                () -> rateLimitService.checkAllowed(1002L, 7002L)
        );
    }
}
