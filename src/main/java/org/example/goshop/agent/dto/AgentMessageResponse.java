package org.example.goshop.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.entity.AgentMessageRole;
import org.example.goshop.agent.entity.AgentMessageStatus;

import java.time.LocalDateTime;
import java.util.List;
/**
 * Agent 页面展示的一条可见消息。
 *
 * <p>该 DTO 只返回买家与助手可见的数据，不包含系统提示词、
 * 模型供应商原始响应、工具参数或完整工具结果。</p>
 */
@Schema(
        name = "AgentMessageResponse",
        description = "买家与购物 Agent 的可见消息"
)
public record AgentMessageResponse(
        @Schema(
                description = "消息 ID，同时作为历史分页游标",
                example = "2041290571319791616",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long id,

        @Schema(
                description = "消息所属会话 ID",
                example = "2041290474313932800",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long conversationId,

        @Schema(
                description = "消息角色：USER 或 ASSISTANT",
                example = "ASSISTANT",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        AgentMessageRole role,

        @Schema(
                description = "纯文本消息正文；前端必须转义 HTML",
                example = "根据你的预算，我找到了以下几款办公键盘。",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String content,

        @Schema(
                description = "消息状态：STREAMING、COMPLETED 或 FAILED",
                example = "COMPLETED",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        AgentMessageStatus status,

        @Schema(
                description = "用户消息幂等 UUID；助手消息为 null",
                example = "2d451a7d-0a6d-44d8-8b69-c665691b1c42",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        String clientMessageId,

        @Schema(
                description = "助手消息对应的运行 ID；用户消息为 null",
                example = "2041290611238912000",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        Long runId,

        @Schema(
                description = "消息创建时间",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime createdAt,

        @Schema(
                description = "消息完成或失败时间；正在生成时为 null",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        LocalDateTime completedAt,

        @Schema(
                description = "助手消息的安全商品/订单结果卡片；用户消息为空数组",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<AgentResultCardData> resultCards
) {
    public AgentMessageResponse {
        resultCards = resultCards == null
                ? List.of()
                : List.copyOf(resultCards);
    }

    /** 将持久化实体转换为安全的对外响应。 */
    public static AgentMessageResponse from(AgentMessage message) {
        return from(message, List.of());
    }

    /** 将实体与已经校验、反序列化的卡片共同转换为响应。 */
    public static AgentMessageResponse from(
            AgentMessage message,
            List<AgentResultCardData> resultCards
    ) {
        return new AgentMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getClientMessageId(),
                message.getRunId(),
                message.getCreatedAt(),
                message.getCompletedAt(),
                resultCards
        );
    }
}
