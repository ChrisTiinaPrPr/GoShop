package org.example.goshop.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.goshop.agent.entity.AgentConversation;

import java.time.LocalDateTime;

/**
 * 买家看到的 Agent 会话摘要。
 *
 * <p>响应中不返回 userId。会话归属由后端根据 JWT 校验，
 * 前端既不需要知道，也不能使用 userId 判断权限。</p>
 */
@Schema(
        name = "AgentConversationResponse",
        description = "买家购物 Agent 会话摘要"
)
public record AgentConversationResponse(
        @Schema(
                description = "会话 ID",
                example = "2041290474313932800",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long id,

        @Schema(
                description = "会话标题",
                example = "预算 300 元的办公键盘",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String title,

        @Schema(
                description = "会话最后一条消息 ID；空会话时为 null",
                example = "2041290571319791616",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        Long lastMessageId,

        @Schema(
                description = "最后一条消息完成时间；空会话时为 null",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        LocalDateTime lastMessageAt,

        @Schema(
                description = "会话创建时间",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime createdAt
) {
    /**
     * 将数据库实体转换为对外响应。
     *
     * <p>转换集中在 DTO 内，Controller 和 Service 不需要重复组装字段。</p>
     */
    public static AgentConversationResponse from(
            AgentConversation conversation
    ) {
        return new AgentConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getLastMessageId(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt()
        );
    }
}
