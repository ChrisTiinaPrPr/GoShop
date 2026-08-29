package org.example.goshop.merchant.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

/** 商家上传一份导购文档的 multipart 表单。 */
@Schema(
        name = "UploadMerchantAiDocumentRequest",
        description = "导购文档上传表单；真实格式与大小由服务端二次校验"
)
public record UploadMerchantAiDocumentRequest(
        @Schema(
                description = "PDF、DOCX、TXT 或 Markdown 文件，最大 5 MB",
                type = "string",
                format = "binary",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "导购文档不能为空")
        MultipartFile file
) {

    /** MultipartFile 对象存在时仍可能是零字节文件。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "导购文档不能为空文件")
    public boolean isFileNotEmpty() {
        return file == null || !file.isEmpty();
    }
}
