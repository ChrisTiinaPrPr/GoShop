package org.example.goshop.chat.dto;

/**
 * 服务端通过 /user/queue/chat.events 推送的事件类型。
 */
public enum ChatEventType {

    /** 创建了新消息。 */
    MESSAGE_CREATED,

    /** 买家或商家推进了已读游标。 */
    MESSAGE_READ,

    /** 会话摘要发生变化，例如最后消息或未读数量变化。 */
    CONVERSATION_UPDATED

}
