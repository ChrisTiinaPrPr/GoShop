package org.example.goshop.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.example.goshop.chat.dto.ChatEventResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 向买家端和商家端的用户专属队列推送聊天事件。
 *
 * <p>前端统一订阅 /user/queue/chat.events。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketPublisher {

    private static final String CHAT_EVENT_QUEUE = "/queue/chat.events";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 推送给指定买家的全部在线连接。
     *
     * <p>Principal 名称必须与 ChatPrincipal.getName() 保持一致。</p>
     */
    public void sendToBuyer(Long buyerUserId, ChatEventResponse event) {
        messagingTemplate.convertAndSendToUser(
                "buyer:" + buyerUserId,
                CHAT_EVENT_QUEUE,
                event
        );
    }

    /**
     * 推送给指定商家的全部在线连接。
     */
    public void sendToMerchant(Long merchangId, ChatEventResponse event) {
        messagingTemplate.convertAndSendToUser(
                "merchant:" + merchangId,
                CHAT_EVENT_QUEUE,
                event
        );
    }


}
