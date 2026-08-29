package org.example.goshop.chat.dto;

import java.time.LocalDateTime;

/**
 * 已读事件载荷。
 *
 * @param readerRole        推进游标的一方，只能是 USER 或 MERCHANT
 * @param lastReadMessageId 最新已读消息 ID
 * @param readAt            服务端处理时间，Asia/Shanghai
 */
public record ChatReadReceiptResponse(
        String readerRole,
        Long lastReadMessageId,
        LocalDateTime readAt
) {
}
