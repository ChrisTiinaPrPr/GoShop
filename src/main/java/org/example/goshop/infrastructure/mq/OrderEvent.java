package org.example.goshop.infrastructure.mq;

import java.time.LocalDateTime;

/**
 * 订单 MQ 事件统一结构。
 *
 * @param eventId   每次业务事件唯一 UUID
 * @param eventType ORDER_CREATED、ORDER_PAID、ORDER_CANCELLED
 * @param orderId   数据库订单主键
 * @param orderNo   用户看到的商城订单号
 * @param paymentNo 支付成功事件对应的支付单号；非支付事件为空
 * @param expireAt  订单支付截止时间
 * @param occurredAt 事件产生时间
 */

public record OrderEvent(
        String eventId,
        String eventType,
        Long orderId,
        String orderNo,
        String paymentNo,
        LocalDateTime expireAt,
        LocalDateTime occurredAt
) {
}
