package org.example.goshop.infrastructure.mq.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mq_outbox")
public class MqOutbox {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String exchangeName;
    private String routingKey;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;

    /**
     * 延迟消息应触发的绝对时间。
     * 发布时通过 deliverAt - 当前时间计算 RabbitMQ TTL。
     */
    private LocalDateTime deliverAt;

    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
