package org.example.goshop.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent SSE 统一事件包络。
 *
 * <p>无论是文本增量、工具进度、确认动作还是失败事件，
 * 都使用完全相同的外层结构。这样前端只需要维护一套解析逻辑。</p>
 *
 * @param eventId       本次事件唯一 ID，用于前端重复事件去重
 * @param type          事件类型
 * @param conversationId 所属会话 ID
 * @param runId         所属 AgentRun ID
 * @param occurredAt    事件产生时间，使用 UTC Instant 避免时区歧义
 * @param data          与 type 对应的具体数据
 */
@Schema(
        name = "AgentSseEvent",
        description = "Agent 流式响应的统一 SSE 事件包络"
)
public record AgentSseEvent<T extends AgentSseData>(
        String eventId,
        AgentSseEventType type,
        Long conversationId,
        Long runId,
        Instant occurredAt,
        T data
) {
    /**
     * 创建一个新的 SSE 事件。
     *
     * <p>eventId 在服务端生成，不能由模型生成，也不能使用工具返回值。
     * 模型只能影响经过过滤后的 data 内容。</p>
     */
    public static <T extends AgentSseData> AgentSseEvent<T> create(
            AgentSseEventType type,
            Long conversationId,
            Long runId,
            T data
    ) {
        Objects.requireNonNull(type, "SSE 事件类型不能为空");
        Objects.requireNonNull(conversationId, "会话 ID 不能为空");
        Objects.requireNonNull(runId, "运行 ID 不能为空");
        Objects.requireNonNull(data, "SSE data 不能为空");

        return new AgentSseEvent<>(
                UUID.randomUUID().toString(),
                type,
                conversationId,
                runId,
                Instant.now(),
                data
        );
    }
}
