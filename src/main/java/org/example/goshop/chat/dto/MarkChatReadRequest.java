package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 单调推进当前会话已读游标的请求。 */
@Schema(name = "MarkChatReadRequest", description = "将当前端已读位置推进到指定消息")
public record MarkChatReadRequest(
        @Schema(
                description = "最后读到的消息 ID；必须属于当前会话且不能小于已有已读游标",
                example = "2041290474313932800",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "最后已读消息 ID 不能为空")
        @Positive(message = "最后已读消息 ID 必须是正数")
        Long lastReadMessageId
) {
}
