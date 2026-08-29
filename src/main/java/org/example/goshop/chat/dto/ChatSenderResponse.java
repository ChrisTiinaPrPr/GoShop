package org.example.goshop.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 消息发送者的公开资料，不包含手机号等账号隐私。 */
@Schema(name = "ChatSenderResponse", description = "聊天消息发送者的公开资料")
public record ChatSenderResponse(
        @Schema(description = "发送者账号 ID", example = "10001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Long userId,

        @Schema(
                description = "发送消息时使用的门户角色",
                allowableValues = {"USER", "MERCHANT"},
                example = "USER",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        @Pattern(regexp = "^(USER|MERCHANT)$")
        String role,

        @Schema(
                description = "商家 ID；role=MERCHANT 时返回，买家消息为空",
                example = "90001",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Positive
        Long merchantId,

        @Schema(description = "聊天展示名称", example = "极光数码", maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 100)
        String displayName,

        @Schema(description = "头像或店铺 Logo 地址", maxLength = 512,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 512)
        String avatarUrl
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "MERCHANT 发送者必须包含 merchantId，USER 发送者不能包含 merchantId")
    public boolean isMerchantIdentityValid() {
        if (role == null) {
            return true;
        }
        return "MERCHANT".equals(role) ? merchantId != null : merchantId == null;
    }
}
