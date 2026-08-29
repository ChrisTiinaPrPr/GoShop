package org.example.goshop.chat.dto;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * WebSocket 聊天事件统一外层结构。
 *
 * <p>不同事件只能携带对应的一种载荷：</p>
 * <ul>
 *     <li>MESSAGE_CREATED：message 不为空；</li>
 *     <li>MESSAGE_READ：readReceipt 不为空；</li>
 *     <li>CONVERSATION_UPDATED：conversation 不为空。</li>
 * </ul>
 */
public record ChatEventResponse(

        /** 本次推送事件的 UUID，用于前端短期去重。 */
        String eventId,

        /** 事件类型。 */
        ChatEventType eventType,

        /** 事件所属会话。 */
        Long conversationId,

        /** MESSAGE_CREATED 的载荷。 */
        ChatMessageResponse message,

        /** MESSAGE_READ 的载荷。 */
        ChatReadReceiptResponse readReceipt,

        /** CONVERSATION_UPDATED 的载荷。 */
        ChatConversationResponse conversation,

        /** 事件生成时间，Asia/Shanghai。 */
        LocalDateTime occurredAt
) {
    public ChatEventResponse {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(eventType, "eventType 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");

        if (conversationId <= 0) {
            throw new IllegalArgumentException("conversationId 必须是正数");
        }

        // 保证事件类型和载荷严格对应，避免前端收到语义模糊的事件。
        boolean valid = switch (eventType) {
            case MESSAGE_CREATED ->
                    message != null
                            && readReceipt == null
                            && conversation == null;

            case MESSAGE_READ ->
                    message == null
                            && readReceipt != null
                            && conversation == null;

            case CONVERSATION_UPDATED ->
                    message == null
                            && readReceipt == null
                            && conversation != null;
        };

        if (!valid) {
            throw new IllegalArgumentException("聊天事件类型与载荷不匹配");
        }
    }

    /** 创建“新消息”事件。 */
    public static ChatEventResponse messageCreated(
            ChatMessageResponse message
    ) {
        return new ChatEventResponse(
                UUID.randomUUID().toString(),
                ChatEventType.MESSAGE_CREATED,
                message.conversationId(),
                message,
                null,
                null,
                LocalDateTime.now()
        );
    }

    /** 创建“已读游标变化”事件。 */
    public static ChatEventResponse messageRead(
            Long conversationId,
            ChatReadReceiptResponse receipt
    ) {
        return new ChatEventResponse(
                UUID.randomUUID().toString(),
                ChatEventType.MESSAGE_READ,
                conversationId,
                null,
                receipt,
                null,
                LocalDateTime.now()
        );
    }

    /** 创建“会话摘要变化”事件。 */
    public static ChatEventResponse conversationUpdated(
            ChatConversationResponse conversation
    ) {
        return new ChatEventResponse(
                UUID.randomUUID().toString(),
                ChatEventType.CONVERSATION_UPDATED,
                conversation.id(),
                null,
                null,
                conversation,
                LocalDateTime.now()
        );
    }
}
