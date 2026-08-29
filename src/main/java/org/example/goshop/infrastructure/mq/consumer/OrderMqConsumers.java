package org.example.goshop.infrastructure.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.infrastructure.mq.OrderEvent;
import org.example.goshop.infrastructure.mq.RabbitMqNames;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 消息入口。
 *
 * <p>方法正常返回时 Spring 才会 ACK。</p>
 * <p>反序列化或业务执行抛出异常时，进入 Spring Retry。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderMqConsumers {

    private final ObjectMapper objectMapper;
    private final OrderMqConsumerService consumerService;

    @RabbitListener(queues = RabbitMqNames.ORDER_TIMEOUT_QUEUE)
    public void consumeOrderTimeout(Message message) {
        consumerService.handleOrderTimeout(readEvent(message));
    }

    @RabbitListener(queues = RabbitMqNames.STOCK_RESTORE_QUEUE)
    public void consumeStockRestore(Message message) {
        consumerService.handleStockRestore(readEvent(message));
    }

    @RabbitListener(queues = RabbitMqNames.ORDER_PAID_NOTIFICATION_QUEUE)
    public void consumePaidNotification(Message message) {
        consumerService.handlePaidNotification(readEvent(message));
    }

    /**
     * 将 MQ 中的 UTF-8 JSON 转成 OrderEvent。
     *
     * <p>不吞掉异常。坏消息应该在重试结束后进入死信队列，
     * 不能假装消费成功。</p>
     */
    private OrderEvent readEvent(Message message) {
        try {
            return objectMapper.readValue(
                    message.getBody(),
                    OrderEvent.class
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "订单 MQ 消息 JSON 解析失败，messageId="
                            + message.getMessageProperties().getMessageId(),
                    exception
            );
        }
    }
}
