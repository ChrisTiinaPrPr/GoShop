package org.example.goshop.infrastructure.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑配置。
 *
 * <p>应用启动后，Spring Boot 的 RabbitAdmin 会自动在 RabbitMQ 中
 * 声明这些交换机、队列和绑定。</p>
 *
 * <p>所有交换机和队列都使用 durable=true，RabbitMQ 重启后定义仍然存在。
 * 生产者发送的消息还会设置 deliveryMode=PERSISTENT。</p>
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(
                RabbitMqNames.ORDER_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(
                RabbitMqNames.ORDER_TIMEOUT_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(
                RabbitMqNames.DEAD_LETTER_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 延迟队列。
     *
     * <p>这个队列故意不配置 @RabbitListener。消息进入后只等待 TTL 到期。
     * TTL 到期时 RabbitMQ 根据下面的死信配置，把消息重新投递到
     * ORDER_TIMEOUT_EXCHANGE。</p>
     *
     * <p>TTL 不固定写在队列参数中，而是由每条消息根据订单 expireAt 单独设置。
     * 这样即使 RabbitMQ 宕机 5 分钟，恢复后仍能按照订单原始 expireAt
     * 计算剩余延迟，而不是重新等待完整 30 分钟。</p>
     */
    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder
                .durable(RabbitMqNames.ORDER_TIMEOUT_DELAY_QUEUE)
                .deadLetterExchange(RabbitMqNames.ORDER_TIMEOUT_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqNames.ORDER_TIMEOUT_KEY)
                .build();
    }

    /**
     * 超时取消业务队列。
     *
     * <p>处理连续失败且重试耗尽时，消息被拒绝；
     * RabbitMQ 会将其投递到 DEAD_LETTER_EXCHANGE。</p>
     */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder
                .durable(RabbitMqNames.ORDER_TIMEOUT_QUEUE)
                .deadLetterExchange(RabbitMqNames.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqNames.DEAD_ORDER_TIMEOUT_KEY)
                .build();
    }

    @Bean
    public Queue orderPaidNotificationQueue() {
        return QueueBuilder
                .durable(RabbitMqNames.ORDER_PAID_NOTIFICATION_QUEUE)
                .deadLetterExchange(RabbitMqNames.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqNames.DEAD_ORDER_PAID_KEY)
                .build();
    }

    @Bean
    public Queue stockRestoreQueue() {
        return QueueBuilder
                .durable(RabbitMqNames.STOCK_RESTORE_QUEUE)
                .deadLetterExchange(RabbitMqNames.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqNames.DEAD_STOCK_RESTORE_KEY)
                .build();
    }

    /**
     * 死信队列不要编写自动 ACK 的消费者。
     *
     * <p>保留消息，方便在 RabbitMQ 管理后台查看 payload 和 x-death，
     * 修复问题后再人工重放。</p>
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(RabbitMqNames.DEAD_LETTER_QUEUE)
                .build();
    }

    // order.created 先进入延迟队列
    // 当消息发送到 goshop.order.exchange 时，并且消息的 routingKey 是 order.created 时，
    // 将消息路由到 goshop.order.timeout.delay.queue
    @Bean
    public Binding orderCreatedDelayBinding(
            Queue orderTimeoutDelayQueue,
            TopicExchange orderExchange
    ) {
        return BindingBuilder
                .bind(orderTimeoutDelayQueue)
                .to(orderExchange)
                .with(RabbitMqNames.ORDER_CREATED_KEY);
    }
    // 延迟消息 TTL 到期后进入真正的超时处理队列
    @Bean
    public Binding orderTimeoutBinding(
            Queue orderTimeoutQueue,
            DirectExchange orderTimeoutExchange
    ) {
        return BindingBuilder
                .bind(orderTimeoutQueue)
                .to(orderTimeoutExchange)
                .with(RabbitMqNames.ORDER_TIMEOUT_KEY);
    }

    @Bean
    public Binding orderPaidBinding(
            Queue orderPaidNotificationQueue,
            TopicExchange orderExchange
    ) {
        return BindingBuilder
                .bind(orderPaidNotificationQueue)
                .to(orderExchange)
                .with(RabbitMqNames.ORDER_PAID_KEY);
    }

    @Bean
    public Binding stockRestoreBinding(
            Queue stockRestoreQueue,
            TopicExchange orderExchange
    ) {
        return BindingBuilder
                .bind(stockRestoreQueue)
                .to(orderExchange)
                .with(RabbitMqNames.ORDER_CANCELLED_KEY);
    }

    @Bean
    public Binding deadLetterBinding(
            Queue deadLetterQueue,
            TopicExchange deadLetterExchange
    ) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with("dead.#");
    }
}
