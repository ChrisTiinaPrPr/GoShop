package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** IMAGE 消息的安全访问数据。 */
@Schema(name = "ChatImageResponse", description = "聊天图片信息；URL 过期后需重新查询消息获取")
public record ChatImageResponse(
        @Schema(description = "私有 OSS 对象的短时效签名 URL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 2048)
        String url,

        @Schema(
                description = "服务端根据文件内容识别的 MIME 类型",
                allowableValues = {"image/jpeg", "image/png", "image/webp"},
                example = "image/webp",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        @Pattern(regexp = "^image/(jpeg|png|webp)$")
        String mimeType,

        @Schema(description = "图片实际字节数", example = "102400", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Long sizeBytes,

        @Schema(description = "图片像素宽度", example = "1280", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Positive
        Integer width,

        @Schema(description = "图片像素高度", example = "720", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Positive
        Integer height,

        @Schema(description = "签名 URL 过期时间（UTC）", example = "2026-08-04T09:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Instant expiresAt
) {
}
