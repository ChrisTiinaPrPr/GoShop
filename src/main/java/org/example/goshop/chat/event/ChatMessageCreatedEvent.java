package org.example.goshop.chat.event;

import org.example.goshop.chat.dto.ChatConversationResponse;
import org.example.goshop.chat.dto.ChatMessageResponse;

/**
 * 聊天消息数据库事务成功提交后需要推送的内部事件。
 *
 * <p>这是 Spring 应用内部事件，不是 RabbitMQ 消息。</p>
 *
 * @param buyerUserId         买家 sys_user.id
 * @param merchantId          商家 merchant.id
 * @param message             新消息
 * @param buyerConversation   买家视角的会话摘要
 * @param merchantConversation 商家视角的会话摘要
 */
public record ChatMessageCreatedEvent(
        Long buyerUserId,
        Long merchantId,
        ChatMessageResponse message,
        ChatConversationResponse buyerConversation,
        ChatConversationResponse merchantConversation
) {
}
