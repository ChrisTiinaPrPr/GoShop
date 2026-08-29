package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天消息的持久化类型。
 *
 * <p>枚举名称与数据库 {@code chat_message.message_type} 的 CHECK 约束保持一致，
 * 禁止使用 ordinal 持久化，避免调整枚举顺序后破坏历史数据。</p>
 */
@Schema(
        name = "ChatMessageType",
        description = "消息类型：TEXT=纯文本，IMAGE=单张图片，ORDER=订单卡片",
        allowableValues = {"TEXT", "IMAGE", "ORDER"},
        example = "TEXT"
)
public enum ChatMessageType {
    TEXT,
    IMAGE,
    ORDER
}
