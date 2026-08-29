package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 商家知识库检索响应。 */
@Schema(
        name = "MerchantAiKnowledgeSearchResponse",
        description = "按相似度排序且已经完成租户和文档状态复核的知识分片"
)
public record MerchantAiKnowledgeSearchResponse(
        String query,
        Integer requestedTopK,
        Double similarityThreshold,
        Integer matchCount,
        List<MerchantAiKnowledgeMatchResponse> matches
) {
}
