package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 一条经过 MySQL 有效性复核的知识分片召回结果。 */
@Schema(
        name = "MerchantAiKnowledgeMatchResponse",
        description = "智能导购知识库命中的文档分片"
)
public record MerchantAiKnowledgeMatchResponse(
        Long documentId,
        String originalFilename,
        Integer chunkIndex,
        String content,
        @Schema(
                description = "Qdrant 原始向量相似度；商品型号或中文关键词命中时可能低于纯语义阈值，结果顺序以本地综合重排为准"
        )
        Double score
) {
}
