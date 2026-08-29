package org.example.goshop.infrastructure.mq;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * GoShop MQ 业务配置。
 *
 * <p>Duration 支持 application.yml 中的 30m、60s、1h 等格式。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "goshop.mq")
public class MqProperties {

    // 订单创建后允许支付的最长时间，默认 30 分钟
    private Duration orderTimeout = Duration.ofMinutes(30);

    // Outbox 批量发送条数,Outbox 定时任务每次最多从 mq_outbox 表中取多少条待发送消息。
    private int outboxBatchSize = 100;
}
