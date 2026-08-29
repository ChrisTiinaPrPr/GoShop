package org.example.goshop.chat.event;

import org.example.goshop.chat.dto.ChatConversationResponse;
import org.example.goshop.chat.dto.ChatReadReceiptResponse;

/**
 * 已读游标成功推进后的内部事件。
 */
public record ChatReadAdvancedEvent(
        Long buyerUserId,
        Long merchantId,
        ChatReadReceiptResponse receipt,
        ChatConversationResponse buyerConversation,
        ChatConversationResponse merchantConversation
) {
}
