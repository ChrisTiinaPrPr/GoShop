package org.example.goshop.chat.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.chat.dto.ChatEventResponse;
import org.example.goshop.chat.event.ChatMessageCreatedEvent;
import org.example.goshop.chat.event.ChatReadAdvancedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 数据库事务成功提交后，将聊天事件推送到 WebSocket。
 *
 * <p>必须使用 AFTER_COMMIT：</p>
 * <ul>
 *     <li>数据库回滚时，不允许推送不存在的消息；</li>
 *     <li>WebSocket 推送失败时，不能回滚已经保存的聊天消息；</li>
 *     <li>断线用户之后可以通过 REST 从 MySQL 补拉。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRealtimeEventListener {

    private final ChatWebSocketPublisher publisher;

    /**
     * 新消息事务提交成功后，同时通知买家和商家。
     *
     * <p>发送方也需要接收事件，因为同一账号可能同时打开多个浏览器标签页。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(ChatMessageCreatedEvent event) {
        ChatEventResponse messageEvent = ChatEventResponse.messageCreated(event.message());

        safeSendBuyer(
                event.buyerUserId(),
                messageEvent,
                "推送买家新消息事件失败"
        );

        safeSendMerchant(
                event.merchantId(),
                messageEvent,
                "推送商家新消息事件失败"
        );

        // 买家、商家的未读数量和对方资料不同，所以分别构造会话事件。
        safeSendBuyer(
                event.buyerUserId(),
                ChatEventResponse.conversationUpdated(
                        event.buyerConversation()
                ),
                "推送买家会话摘要失败"
        );

        safeSendMerchant(
                event.merchantId(),
                ChatEventResponse.conversationUpdated(
                        event.merchantConversation()
                ),
                "推送商家会话摘要失败"
        );
    }

    /**
     * 已读游标推进后通知双方。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReadAdvanced(ChatReadAdvancedEvent event) {
        ChatEventResponse readEvent = ChatEventResponse.messageRead(event.buyerConversation().id(),event.receipt());

        safeSendBuyer(
                event.buyerUserId(),
                readEvent,
                "推送买家已读事件失败"
        );

        safeSendMerchant(
                event.merchantId(),
                readEvent,
                "推送商家已读事件失败"
        );

        safeSendBuyer(
                event.buyerUserId(),
                ChatEventResponse.conversationUpdated(
                        event.buyerConversation()
                ),
                "推送买家会话摘要失败"
        );

        safeSendMerchant(
                event.merchantId(),
                ChatEventResponse.conversationUpdated(
                        event.merchantConversation()
                ),
                "推送商家会话摘要失败"
        );
    }

    /**
     * 单个目的地推送失败不能影响其他目的地。
     */
    private void safeSendBuyer(Long buyerUserId, ChatEventResponse event, String errorMessage) {
        try {
            publisher.sendToBuyer(buyerUserId, event);
        } catch (RuntimeException exception) {
            log.error(
                    "{}, buyerUserId={}, eventType={}",
                    errorMessage,
                    buyerUserId,
                    event.eventType(),
                    exception
            );
        }
    }

    private void safeSendMerchant(Long merchantId, ChatEventResponse event, String errorMessage) {
        try {
            publisher.sendToMerchant(merchantId, event);
        } catch (RuntimeException exception) {
            log.error(
                    "{}, merchantId={}, eventType={}",
                    errorMessage,
                    merchantId,
                    event.eventType(),
                    exception
            );
        }
    }
}
