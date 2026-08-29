package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 一条买家可见的导购知识来源。 */
@Schema(
        name = "BuyerMerchantAiCitationResponse",
        description = "智能导购回答引用的店铺文档分片"
)
public record BuyerMerchantAiCitationResponse(
        @Schema(description = "回答中的引用编号，从1开始")
        int citationNo,
        Long documentId,
        String originalFilename,
        Integer chunkIndex,
        @Schema(description = "经过长度限制的引用摘要")
        String excerpt,
        @Schema(description = "Qdrant 原始向量相似度")
        Double score
) {
}
