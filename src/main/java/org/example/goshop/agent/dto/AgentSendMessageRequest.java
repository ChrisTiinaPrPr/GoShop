package org.example.goshop.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 买家向 Agent 发送消息的请求。
 *
 * <p>clientMessageId 由浏览器生成。网络中断或请求重试时，
 * 前端必须复用原来的 clientMessageId，不能重新生成。</p>
 *
 * <p>服务端通过 conversationId + clientMessageId 唯一约束，
 * 保证同一条用户消息只会创建一次 AgentRun。</p>
 */
@Schema(
        name = "AgentSendMessageRequest",
        description = "向购物 Agent 发送消息"
)
public record AgentSendMessageRequest(
        @Schema(
                description = "浏览器生成的消息幂等 UUID；重试时必须保持不变",
                example = "2f39ea3d-e27d-4ce1-90f6-a695208e1844",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "clientMessageId 不能为空")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}$",
                message = "clientMessageId 必须是标准 UUID"
        )
        String clientMessageId,

        @Schema(
                description = "用户发送的纯文本内容；默认最多 1000 字",
                example = "帮我推荐一款价格在 300 元以内的无线耳机",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "消息内容不能为空")
        /*
         * 4000 是配置允许的绝对上限。
         *
         * 实际限制还要在 Service 中根据
         * AgentProperties.maxInputChars() 再次校验。
         * 默认配置是 1000 字。
         */
        @Size(
                max = 4000,
                message = "消息内容不能超过 4000 个字符"
        )
        String content
) {
}
