package org.example.goshop.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 聊天客户端入站 STOMP 帧安全拦截器。
 *
 * <p>职责分为三部分：CONNECT 时建立 JWT 身份；SUBSCRIBE 时只允许用户专属聊天事件队列；
 * SEND 时全部拒绝。首版消息写入必须调用 REST，避免 STOMP 写入绕过 Bean Validation、
 * 幂等键和统一异常响应。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatStompChannelInterceptor implements ChannelInterceptor {

    public static final String CHAT_EVENT_DESTINATION = "/user/queue/chat.events";

    private final ChatStompAuthenticationService authenticationService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message,
                StompHeaderAccessor.class
        );
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            throw new AccessDeniedException("聊天消息必须通过 REST 接口发送");
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        Authentication authentication = authenticationService.authenticate(authorization);
        accessor.setUser(authentication);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("未认证连接不能订阅聊天事件");
        }
        if (!CHAT_EVENT_DESTINATION.equals(accessor.getDestination())) {
            throw new AccessDeniedException("只允许订阅当前用户的聊天事件队列");
        }
    }
}
