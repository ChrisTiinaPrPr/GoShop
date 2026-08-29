package org.example.goshop.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.dto.SubmitOrderItemRequest;
import org.example.goshop.order.dto.SubmitOrderRequest;
import org.example.goshop.order.dto.SubmitOrderResponse;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 快速幂等层与数据库有限重试的单元测试。 */
class OrderServiceTest {

    private static final Long USER_ID = 1001L;
    private static final String IDEMPOTENCY_KEY = "checkout-1001";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private OrderTransactionService transactionService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        transactionService = mock(OrderTransactionService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        orderService = new OrderService(
                redisTemplate,
                new ObjectMapper(),
                transactionService,
                mock(MallOrderMapper.class),
                mock(OrderItemMapper.class)
        );
    }

    /** MySQL 死锁前两次失败、第三次成功时，应开启新事务有限重试并返回成功结果。 */
    @Test
    void shouldRetryTransientDatabaseFailureAndSucceedOnThirdAttempt() {
        SubmitOrderResponse expected = new SubmitOrderResponse(List.of(), 0L);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(transactionService.createOrdersIdempotently(
                any(),
                anyString(),
                anyString(),
                any()
        ))
                .thenThrow(new CannotAcquireLockException("deadlock-1"))
                .thenThrow(new CannotAcquireLockException("deadlock-2"))
                .thenReturn(expected);

        SubmitOrderResponse actual = orderService.submitOrder(
                USER_ID,
                IDEMPOTENCY_KEY,
                request(1)
        );

        assertEquals(expected, actual);
        verify(transactionService, org.mockito.Mockito.times(3))
                .createOrdersIdempotently(any(), anyString(), anyString(), any());
    }

    /** 连续死锁达到上限后必须停止，避免无限重试持续占用请求线程。 */
    @Test
    void shouldStopRetryingAfterThreeTransientDatabaseFailures() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(transactionService.createOrdersIdempotently(
                any(),
                anyString(),
                anyString(),
                any()
        )).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThrows(
                CannotAcquireLockException.class,
                () -> orderService.submitOrder(
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        request(1)
                )
        );

        verify(transactionService, org.mockito.Mockito.times(3))
                .createOrdersIdempotently(any(), anyString(), anyString(), any());
    }

    /** Redis 中正在处理的摘要不同，应在进入数据库和扣库存之前直接返回 40901。 */
    @Test
    void shouldRejectDifferentBodyWhenRedisKeyIsAlreadyProcessing() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(anyString()))
                .thenReturn("PROCESSING:different-request-hash");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderService.submitOrder(
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        request(2)
                )
        );

        assertEquals(40901, exception.getCode());
        verify(transactionService, never())
                .createOrdersIdempotently(any(), anyString(), anyString(), any());
    }

    /** 升级窗口内仍应复用旧版 Redis 直接保存的响应，避免迁移表没有历史记录时重复下单。 */
    @Test
    void shouldReuseLegacyCachedResponseDuringRollingUpgrade() throws Exception {
        SubmitOrderResponse legacy = new SubmitOrderResponse(List.of(), 0L);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(anyString()))
                .thenReturn(new ObjectMapper().writeValueAsString(legacy));

        SubmitOrderResponse actual = orderService.submitOrder(
                USER_ID,
                IDEMPOTENCY_KEY,
                request(1)
        );

        assertEquals(legacy, actual);
        verify(transactionService, never())
                .createOrdersIdempotently(any(), anyString(), anyString(), any());
    }

    private SubmitOrderRequest request(int quantity) {
        return new SubmitOrderRequest(
                2001L,
                List.of(new SubmitOrderItemRequest(3001L, quantity))
        );
    }
}
