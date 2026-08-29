package org.example.goshop.infrastructure.mq.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 负责将单条 Outbox 发送到 RabbitMQ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqOutboxPublishService {

    private static final int MAX_PUBLISH_RETRY = 10;

    private final MqOutboxMapper mqOutboxMapper;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 每条 Outbox 使用独立事务。
     *
     * <p>一条消息发送失败不会回滚本批次其他已经发送成功的消息。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOne(Long outboxId) {
        MqOutbox outbox = mqOutboxMapper.selectByIdForUpdate(outboxId);

        if (outboxId == null) {
            return;
        }

        if (!"NEW".equals(outbox.getStatus()) && !"RETRY".equals(outbox.getStatus())) {
            // 可能已被另一个应用实例发送，不再重复处理
            return;
        }

        try {
            // RabbitMQ 消息属性类，一条 RabbitMQ 消息由消息正文和属性组成
            /*
            * Body:
            {
              "eventId": "...",
              "orderId": 10001
            }

            Properties:
            contentType = application/json
            contentEncoding = UTF-8
            deliveryMode = PERSISTENT
            messageId = 事件UUID
            expiration = 1800000
            timestamp = 消息发送时间
            headers.eventType = ORDER_CREATED
            */
            MessageProperties properties = new MessageProperties();

            // 消息内容是 UTF-8 JSON
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());

            // 持久消息会写入 RabbitMQ 磁盘
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

            // messageId 使用事件 UUID，方便日志和管理后台追踪
            properties.setMessageId(outbox.getEventId());
            properties.setTimestamp(new Date());
            properties.setHeader("eventType", outbox.getEventType());

            if (outbox.getDeliverAt() != null) {
                /*
                 * RabbitMQ expiration 表示“进入队列后还能存活多少毫秒”，
                 * 不是绝对时间。
                 *
                 * 所以必须在真正发送时使用 deliverAt - 当前时间重新计算。
                 * 如果 RabbitMQ 曾宕机 5 分钟，不会重新等待完整 30 分钟。
                 */
                long remainingMillis = Duration.between(LocalDateTime.now(), outbox.getDeliverAt()).toMillis();

                properties.setExpiration(String.valueOf(remainingMillis));
            }

            Message message = new Message(
                    outbox.getPayloadJson().getBytes(StandardCharsets.UTF_8),
                    properties
            );

            // CorrelationData 是生产者发送消息时携带的“回执关联对象”，用来等待并识别 RabbitMQ 对这条消息返回的 ACK、NACK 或无法路由的 Return。
            CorrelationData correlationData = new CorrelationData(outbox.getEventId());
            rabbitTemplate.send(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    message,
                    correlationData
            );
            /*
             * 等待 RabbitMQ publisher confirm。
             * send() 方法调用成功只说明客户端把消息写到了连接中，不代表 RabbitMQ 已经接收并持久化。
             */
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5,TimeUnit.SECONDS);

            if (!confirm.ack()) {
                throw new IllegalStateException(
                        "RabbitMQ 返回 NACK：" + confirm.reason()
                );
            }

            /*
             * ACK 只代表交换机接收了消息。
             * 如果 routing key 没有匹配队列，mandatory=true 时会触发 returned。
             */
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "RabbitMQ 消息无法路由："
                                + correlationData.getReturned().getReplyText()
                );
            }
            mqOutboxMapper.markSent(outboxId);
            log.info(
                    "MQ Outbox 发送成功，eventId={}，eventType={}",
                    outbox.getEventId(),
                    outbox.getEventType()
            );
        } catch (Exception exception) {
            int retryCount = outbox.getRetryCount() + 1;

            /*
             * 生产者重试采用指数退避，最长 5 分钟：
             * 2、4、8、16……300 秒。
             */
            long retryDelaySeconds = Math.min(
                    300L,
                    1L << Math.min(retryCount,8)
            );

            String status = retryCount >= MAX_PUBLISH_RETRY
                    ? "FAILED"
                    : "RETRY";

            String error = exception.getMessage();
            if (error == null) {
                error = exception.getClass().getName();
            }
            if (error.length() > 1000) {
                error = error.substring(0, 1000);
            }

            mqOutboxMapper.markRetry(
                    outboxId,
                    status,
                    retryCount,
                    LocalDateTime.now().plusSeconds(retryDelaySeconds),
                    error
            );

            log.error(
                    "MQ Outbox 发送失败，eventId={}，retryCount={}，status={}",
                    outbox.getEventId(),
                    retryCount,
                    status,
                    exception
            );
        }
    }
}
