package org.example.goshop.chat.websocket;

import java.security.Principal;

/**
 * 一个已通过 STOMP CONNECT 鉴权的聊天连接身份。
 *
 * <p>项目允许同一 {@code sys_user} 同时拥有 USER 和 MERCHANT 角色。如果只用 userId
 * 作为 WebSocket Principal 名称，发送给买家的用户队列也会被该账号的商家端连接收到。
 * 因此买家连接使用 {@code buyer:userId}，商家连接使用 {@code merchant:merchantId}，
 * 从用户队列寻址层面隔离两个门户。</p>
 *
 * @param userId     当前登录账号的 sys_user.id
 * @param role       当前门户活动角色，只允许 USER 或 MERCHANT
 * @param merchantId 商家门户对应的 merchant.id；买家门户必须为空
 */
public record ChatPrincipal(Long userId, String role, Long merchantId) implements Principal {

    public ChatPrincipal {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("聊天用户 ID 无效");
        }
        if (!"USER".equals(role) && !"MERCHANT".equals(role)) {
            throw new IllegalArgumentException("聊天连接角色无效");
        }
        if ("MERCHANT".equals(role) && (merchantId == null || merchantId <= 0)) {
            throw new IllegalArgumentException("商家聊天连接缺少 merchantId");
        }
        if ("USER".equals(role) && merchantId != null) {
            throw new IllegalArgumentException("买家聊天连接不能携带 merchantId");
        }
    }

    /**
     * Spring 的 {@code /user/**} 队列使用 Principal name 寻址。
     * 名称中加入门户类型，确保双角色账号的买家端和商家端不会互相串消息。
     */
    @Override
    public String getName() {
        return "USER".equals(role)
                ? "buyer:" + userId
                : "merchant:" + merchantId;
    }
}
