package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建或完整更新商家智能导购助手的请求。
 *
 * <p>merchantId、模型、系统提示词和工具权限都不允许由商家提交。
 * 商家只能修改买家可见的展示信息和启用开关。</p>
 */
@Schema(
        name = "SaveMerchantAiAssistantRequest",
        description = "保存当前商家的智能导购助手配置"
)
public record SaveMerchantAiAssistantRequest(
        @Schema(description = "助手名称", example = "星环选购顾问")
        @NotBlank(message = "助手名称不能为空")
        @Size(max = 60, message = "助手名称不能超过60个字符")
        String name,

        @Schema(
                description = "助手头像 HTTP(S) URL；空字符串表示使用店铺 Logo",
                nullable = true
        )
        @Size(max = 500, message = "助手头像地址不能超过500个字符")
        @Pattern(
                regexp = "^\\s*$|^https?://\\S+$",
                message = "助手头像地址必须是有效的 HTTP(S) URL"
        )
        String avatarUrl,

        @Schema(
                description = "买家进入助手时看到的欢迎语",
                example = "您好，请告诉我您的使用场景。"
        )
        @NotBlank(message = "欢迎语不能为空")
        @Size(max = 500, message = "欢迎语不能超过500个字符")
        String welcomeMessage,

        @Schema(description = "是否允许买家使用", example = "false")
        @NotNull(message = "启用状态不能为空")
        Boolean enabled
) {
}
