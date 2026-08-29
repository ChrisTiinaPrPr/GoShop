package org.example.goshop.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.example.goshop.chat.config.ChatProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 联系商家功能的 STOMP over WebSocket 配置。
 *
 * <p>客户端通过 {@code /ws/chat} 建立原生 WebSocket，并只订阅
 * {@code /user/queue/chat.events}。服务端后续使用
 * {@code SimpMessagingTemplate.convertAndSendToUser(principalName, "/queue/chat.events", event)}
 * 定向推送；简单 Broker 只处理 {@code /queue} 前缀。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String CHAT_ENDPOINT = "/ws/chat";

    private final ChatProperties properties;
    private final ChatStompChannelInterceptor stompChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(CHAT_ENDPOINT)
                // 仅允许显式配置的买家端和商家端站点发起浏览器握手，不启用 SockJS。
                .setAllowedOrigins(properties.allowedOriginsArray());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /user 是 Spring 用户目的地前缀，最终会被解析为当前 Principal 的私有队列。
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue");

        // 预留 /app 作为应用目的地，但入站拦截器会拒绝首版所有客户端 SEND 帧。
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }
}
