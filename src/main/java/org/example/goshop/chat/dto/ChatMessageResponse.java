package org.example.goshop.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * REST 历史查询、发送结果和 WebSocket MESSAGE_CREATED 事件共用的消息响应。
 *
 * <p>统一模型避免双端为三种消息维护不同列表结构。根据 {@code type}，
 * {@code content}、{@code image}、{@code orderCard} 必须且只能有一个有效载荷。</p>
 */
@Schema(name = "ChatMessageResponse", description = "统一聊天消息响应")
public record ChatMessageResponse(
        @Schema(description = "服务端消息 ID，同时用作历史和断线补偿游标", example = "2041290474313932800",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Long id,

        @Schema(description = "所属会话 ID", example = "2041286000000000000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Long conversationId,

        @Schema(description = "客户端幂等 UUID", example = "0ec5b9b4-2b87-4be7-9da4-a699cb8cc1ad",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String clientMessageId,

        @Schema(description = "消息类型", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        ChatMessageType type,

        @Schema(description = "发送者公开资料", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Valid
        ChatSenderResponse sender,

        @Schema(description = "TEXT 消息正文，其他类型为空", maxLength = 2000,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 2000)
        String content,

        @Schema(description = "IMAGE 消息图片，其他类型为空", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        ChatImageResponse image,

        @Schema(description = "ORDER 消息订单卡片，其他类型为空", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        ChatOrderCardResponse orderCard,

        @Schema(
                description = "消息服务端创建时间，数据库与应用统一使用 Asia/Shanghai",
                example = "2026-08-05T16:30:00.123",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        LocalDateTime createdAt
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "消息类型与 content、image、orderCard 载荷不匹配")
    public boolean isPayloadValid() {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case TEXT -> StringUtils.hasText(content) && image == null && orderCard == null;
            case IMAGE -> content == null && image != null && orderCard == null;
            case ORDER -> content == null && image == null && orderCard != null;
        };
    }
}
