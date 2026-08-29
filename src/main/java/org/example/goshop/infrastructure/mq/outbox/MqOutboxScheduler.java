package org.example.goshop.infrastructure.mq.outbox;

import lombok.RequiredArgsConstructor;
import org.example.goshop.infrastructure.mq.MqProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 轮询任务。
 *
 * <p>这里只负责查找待发送 ID，真正发送由 MqOutboxPublishService
 * 使用独立事务完成。</p>
 */
@Component
@RequiredArgsConstructor
public class MqOutboxScheduler {

    private final MqOutboxMapper mqOutboxMapper;
    private final MqOutboxPublishService publishService;
    private final MqProperties mqProperties;

    @Scheduled(fixedDelayString = "${goshop.mq.outbox-publish-interval-ms:1000}")
    public void publishPendingMessages() {
        List<Long> ids = mqOutboxMapper.selectReadyIds(LocalDateTime.now(), mqProperties.getOutboxBatchSize());

        for (Long id : ids) {
            publishService.publishOne(id);
        }
    }
}
