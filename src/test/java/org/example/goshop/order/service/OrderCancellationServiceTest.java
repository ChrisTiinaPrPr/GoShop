package org.example.goshop.order.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 买家主动取消和超时取消共享状态机的单元测试。
 *
 * <p>测试使用 Mockito 替代数据库与 Outbox，重点验证锁定后的状态判断、
 * 幂等行为和持久化调用顺序。真实事务回滚由 Spring {@code @Transactional}
 * 保证，支付/取消并发则由双方共同使用的订单行锁保证。</p>
 */
class OrderCancellationServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final String ORDER_NO = "YG-CANCEL-2001";

    private MallOrderMapper orderMapper;
    private PaymentRecordMapper paymentMapper;
    private MqOutboxService outboxService;
    private OrderCancellationService cancellationService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(MallOrderMapper.class);
        paymentMapper = mock(PaymentRecordMapper.class);
        outboxService = mock(MqOutboxService.class);
        cancellationService = new OrderCancellationService(
                orderMapper,
                paymentMapper,
                outboxService
        );
    }

    /**
     * 正常主动取消必须先关闭未支付支付单，再更新订单，最后写 Outbox。
     */
    @Test
    void shouldCancelOwnedPendingOrderAndCreateOutboxEvent() {
        MallOrder order = pendingOrder();

        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID))
                .thenReturn(order);
        when(orderMapper.updateById(order)).thenReturn(1);

        assertDoesNotThrow(
                () -> cancellationService.cancelByBuyer(USER_ID, ORDER_NO)
        );
        assertEquals("CANCELLED", order.getStatus());

        InOrder persistenceOrder = inOrder(
                paymentMapper,
                orderMapper,
                outboxService
        );
        persistenceOrder.verify(paymentMapper)
                .closeUnpaidRecordsByOrderId(ORDER_ID);
        persistenceOrder.verify(orderMapper).updateById(order);
        persistenceOrder.verify(outboxService).saveOrderCancelled(order);
    }

    /**
     * HTTP 响应丢失后的重复取消应幂等成功，不能重复恢复库存事件。
     */
    @Test
    void shouldTreatAlreadyCancelledOrderAsIdempotentSuccess() {
        MallOrder order = pendingOrder();
        order.setStatus("CANCELLED");

        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID))
                .thenReturn(order);

        assertDoesNotThrow(
                () -> cancellationService.cancelByBuyer(USER_ID, ORDER_NO)
        );

        verifyNoInteractions(paymentMapper, outboxService);
        verify(orderMapper, never()).updateById(order);
    }

    /**
     * 不存在和他人订单统一返回 40401，避免根据错误差异枚举订单归属。
     */
    @Test
    void shouldHideWhetherOrderBelongsToAnotherBuyer() {
        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> cancellationService.cancelByBuyer(USER_ID, ORDER_NO)
        );

        assertEquals(40401, exception.getCode());
        verifyNoInteractions(paymentMapper, outboxService);
    }

    /**
     * 支付事务先完成后，取消请求必须返回状态冲突且不能写取消事件。
     */
    @Test
    void shouldRejectCancellationAfterPaymentWonTheRowLock() {
        MallOrder order = pendingOrder();
        order.setStatus("WAITING_SHIPMENT");

        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID))
                .thenReturn(order);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> cancellationService.cancelByBuyer(USER_ID, ORDER_NO)
        );

        assertEquals(40901, exception.getCode());
        assertEquals("当前订单状态不能取消", exception.getMessage());
        verifyNoInteractions(paymentMapper, outboxService);
        verify(orderMapper, never()).updateById(order);
    }

    /**
     * 超时入口也必须复用同一持久化链路，并返回本次确实发生了取消。
     */
    @Test
    void shouldCancelExpiredPendingOrderThroughSharedFlow() {
        LocalDateTime now = LocalDateTime.now();
        MallOrder order = pendingOrder();
        order.setExpireAt(now.minusSeconds(1));

        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);
        when(orderMapper.updateById(order)).thenReturn(1);

        assertTrue(cancellationService.cancelExpiredOrder(ORDER_ID, now));
        assertEquals("CANCELLED", order.getStatus());
        verify(paymentMapper).closeUnpaidRecordsByOrderId(ORDER_ID);
        verify(outboxService).saveOrderCancelled(order);
    }

    /**
     * 买家或支付已经先处理订单时，迟到的超时消息应正常 ACK 而不重复取消。
     */
    @Test
    void shouldIgnoreTimeoutWhenOrderIsNoLongerPending() {
        MallOrder order = pendingOrder();
        order.setStatus("CANCELLED");

        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);

        assertFalse(
                cancellationService.cancelExpiredOrder(
                        ORDER_ID,
                        LocalDateTime.now()
                )
        );
        verifyNoInteractions(paymentMapper, outboxService);
    }

    /**
     * TTL 消息提前到达时不能伤害仍在有效付款期内的订单。
     */
    @Test
    void shouldRejectEarlyTimeoutMessage() {
        LocalDateTime now = LocalDateTime.now();
        MallOrder order = pendingOrder();
        order.setExpireAt(now.plusMinutes(5));

        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(order);

        assertThrows(
                IllegalStateException.class,
                () -> cancellationService.cancelExpiredOrder(ORDER_ID, now)
        );
        verifyNoInteractions(paymentMapper, outboxService);
        verify(orderMapper, never()).updateById(order);
    }

    private MallOrder pendingOrder() {
        MallOrder order = new MallOrder();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setStatus("PENDING_PAYMENT");
        order.setExpireAt(LocalDateTime.now().plusMinutes(30));
        return order;
    }
}
