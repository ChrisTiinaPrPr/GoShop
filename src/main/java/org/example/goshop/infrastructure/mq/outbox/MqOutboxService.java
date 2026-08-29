package org.example.goshop.infrastructure.mq.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.OrderEvent;
import org.example.goshop.infrastructure.mq.RabbitMqNames;
import org.example.goshop.order.entity.MallOrder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 在业务数据库事务中保存待发送 MQ 事件。
 *
 * <p>这个类不直接调用 RabbitMQ。它只写 mq_outbox，
 * 因此订单更新和事件记录可以在同一个 MySQL 事务内提交或回滚。</p>
 */
@Service
@RequiredArgsConstructor
public class MqOutboxService {

    private final MqOutboxMapper mqOutboxMapper;
    private final ObjectMapper objectMapper;

    public void saveOrderCreated(MallOrder order) {
        saveOrderEvent(
                "ORDER_CREATED",
                RabbitMqNames.ORDER_CREATED_KEY,
                order,
                null,
                // 订单创建消息需要延迟到 expireAt。
                order.getExpireAt()
        );
    }

    public void saveOrderPaid(MallOrder order, String paymentNo) {
        saveOrderEvent(
                "ORDER_PAID",
                RabbitMqNames.ORDER_PAID_KEY,
                order,
                paymentNo,
                // 支付成功事件不延迟，尽快发送。
                null
        );
    }
    public void saveOrderCancelled(MallOrder order) {
        saveOrderEvent(
                "ORDER_CANCELLED",
                RabbitMqNames.ORDER_CANCELLED_KEY,
                order,
                null,
                null
        );
    }

    private void saveOrderEvent(
            String eventType,
            String routingKey,
            MallOrder order,
            String paymentNo,
            LocalDateTime deliverAt
    ) {
        String eventId = UUID.randomUUID().toString();

        OrderEvent event = new OrderEvent(
                eventId,
                eventType,
                order.getId(),
                order.getOrderNo(),
                paymentNo,
                order.getExpireAt(),
                LocalDateTime.now()
        );
        final String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            /*
             * 事件无法序列化时必须让业务事务回滚。
             * 否则会产生“订单已经提交，但永远没有 MQ 事件”的情况。
             */
            throw new BusinessException(50000, "序列化订单 MQ 事件失败");
        }

        MqOutbox outbox = new MqOutbox();
        outbox.setEventId(eventId);
        outbox.setAggregateType("ORDER");
        outbox.setAggregateId(String.valueOf(order.getId()));
        outbox.setEventType(eventType);
        outbox.setExchangeName(RabbitMqNames.ORDER_EXCHANGE);
        outbox.setRoutingKey(routingKey);
        outbox.setPayloadJson(payloadJson);
        outbox.setStatus("NEW");
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(LocalDateTime.now());
        outbox.setDeliverAt(deliverAt);

        mqOutboxMapper.insert(outbox);
    }
}
