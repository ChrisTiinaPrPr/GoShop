package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 聊天会话列表和会话详情使用的统一响应。
 *
 * <p>peer 表示“聊天对方”：</p>
 * <ul>
 *     <li>买家查看时，peer 是商家；</li>
 *     <li>商家查看时，peer 是买家。</li>
 * </ul>
 *
 * <p>这样前端不需要分别处理 buyer、merchant 两套字段。</p>
 */
@Schema(name = "ChatConversationResponse", description = "聊天会话摘要")
public record ChatConversationResponse(

        @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "聊天对方的公开资料", requiredMode = Schema.RequiredMode.REQUIRED)
        ChatSenderResponse peer,

        @Schema(
                description = "会话最后一条消息；刚创建但尚未发送消息时为空",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        ChatMessageResponse lastMessage,

        @Schema(
                description = "当前登录端未读的对方消息数量",
                example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        long unreadCount,

        @Schema(
                description = "当前登录端最后已读消息 ID；从未读过时为空",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        Long lastReadMessageId,

        @Schema(description = "会话创建时间，Asia/Shanghai")
        LocalDateTime createdAt

) {
}
