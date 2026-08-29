package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;

import java.time.LocalDateTime;

/**
 * 商家可见的导购文档元数据。
 *
 * <p>响应不返回私有 OSS objectKey 或临时 URL，防止文档被绕过权限下载。</p>
 */
@Schema(
        name = "MerchantAiDocumentResponse",
        description = "导购文档上传与处理状态"
)
public record MerchantAiDocumentResponse(
        Long id,
        Long assistantId,
        String originalFilename,
        String fileType,
        String mimeType,
        Long sizeBytes,
        String status,
        @Schema(description = "处理失败原因；非 FAILED 状态通常为空")
        String failureReason,
        Integer chunkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static MerchantAiDocumentResponse from(
            MerchantAiDocument document
    ) {
        return new MerchantAiDocumentResponse(
                document.getId(),
                document.getAssistantId(),
                document.getOriginalFilename(),
                document.getFileType(),
                document.getMimeType(),
                document.getSizeBytes(),
                document.getStatus(),
                document.getFailureReason(),
                document.getChunkCount(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
