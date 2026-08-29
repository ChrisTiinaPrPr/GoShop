package org.example.goshop.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.multipart.MultipartFile;

/** 上传一张私有 OSS 图片并原子创建 IMAGE 消息的 multipart 请求。 */
@Schema(
        name = "ChatImageMessageRequest",
        description = "聊天图片消息表单。文件大小和真实格式由 Service 按 ChatProperties 二次校验"
)
public record ChatImageMessageRequest(
        @Schema(
                description = "客户端生成的 UUID 幂等键；上传重试必须复用同一个值",
                example = "9cf2ec46-b301-468a-a1de-177ebf4f1a5c",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "客户端消息 ID 不能为空")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "客户端消息 ID 必须是标准 UUID"
        )
        String clientMessageId,

        @Schema(
                description = "JPEG、PNG 或 WebP 图片文件；默认最大 5 MB",
                type = "string",
                format = "binary",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "聊天图片不能为空")
        MultipartFile file
) {

    /** MultipartFile 非空对象仍可能没有内容，因此补充文件内容校验。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "聊天图片不能为空文件")
    public boolean isFileNotEmpty() {
        return file == null || !file.isEmpty();
    }
}
