package org.example.goshop.chat.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStompChannelInterceptorTest {

    private final ChatStompAuthenticationService authenticationService =
            mock(ChatStompAuthenticationService.class);
    private final ChatStompChannelInterceptor interceptor =
            new ChatStompChannelInterceptor(authenticationService);

    @Test
    void shouldAuthenticateConnectAndAttachPrincipalToStompSession() {
        Authentication authentication = (Authentication) authenticatedBuyer();
        when(authenticationService.authenticate("Bearer test-token")).thenReturn(authentication);
        Message<byte[]> connect = stompMessage(StompCommand.CONNECT, accessor ->
                accessor.setNativeHeader("Authorization", "Bearer test-token")
        );

        Message<?> result = interceptor.preSend(connect, mock());
        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(
                result,
                StompHeaderAccessor.class
        );

        verify(authenticationService).authenticate("Bearer test-token");
        assertSame(authentication, resultAccessor.getUser());
    }

    @Test
    void shouldAllowOnlyAuthenticatedUserChatEventSubscription() {
        Message<byte[]> allowed = stompMessage(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setUser(authenticatedBuyer());
            accessor.setDestination(ChatStompChannelInterceptor.CHAT_EVENT_DESTINATION);
        });

        assertDoesNotThrow(() -> interceptor.preSend(allowed, mock()));

        Message<byte[]> otherDestination = stompMessage(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setUser(authenticatedBuyer());
            accessor.setDestination("/topic/all-chat-messages");
        });
        assertThrows(
                AccessDeniedException.class,
                () -> interceptor.preSend(otherDestination, mock())
        );

        Message<byte[]> anonymous = stompMessage(StompCommand.SUBSCRIBE, accessor ->
                accessor.setDestination(ChatStompChannelInterceptor.CHAT_EVENT_DESTINATION)
        );
        assertThrows(
                AccessDeniedException.class,
                () -> interceptor.preSend(anonymous, mock())
        );
    }

    @Test
    void shouldRejectAllClientSendFrames() {
        Message<byte[]> send = stompMessage(StompCommand.SEND, accessor -> {
            accessor.setUser(authenticatedBuyer());
            accessor.setDestination("/app/chat.send");
        });

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(send, mock()));
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            Consumer<StompHeaderAccessor> customizer
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        customizer.accept(accessor);
        // Spring 的真实 clientInboundChannel 会保留可变 HeaderAccessor，供拦截器写入 Principal。
        // 测试构造消息时也必须保持相同行为，否则 MessageBuilder 会提前冻结消息头。
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Principal authenticatedBuyer() {
        ChatPrincipal principal = new ChatPrincipal(1001L, "USER", null);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
}
