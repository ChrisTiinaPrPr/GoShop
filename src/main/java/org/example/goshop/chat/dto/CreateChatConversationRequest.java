package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 买家按商家获取或创建唯一聊天会话的请求。 */
@Schema(name = "CreateChatConversationRequest", description = "创建或复用买家与指定商家的唯一会话")
public record CreateChatConversationRequest(
        @Schema(
                description = "目标商家 ID；服务端还会校验商家存在且处于启用状态",
                example = "10001",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "商家 ID 不能为空")
        @Positive(message = "商家 ID 必须是正数")
        Long merchantId
) {
}
