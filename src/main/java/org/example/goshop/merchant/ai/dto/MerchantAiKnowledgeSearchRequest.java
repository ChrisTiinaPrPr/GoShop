package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 商家知识库语义检索请求。 */
@Schema(
        name = "MerchantAiKnowledgeSearchRequest",
        description = "商家测试智能导购知识库召回效果的请求"
)
public record MerchantAiKnowledgeSearchRequest(
        @NotBlank(message = "检索问题不能为空")
        @Size(max = 500, message = "检索问题不能超过500个字符")
        String query,

        @Min(value = 1, message = "召回数量不能小于1")
        @Max(value = 10, message = "召回数量不能超过10")
        Integer topK,

        @DecimalMin(value = "0.5", message = "相似度阈值不能小于0.5")
        @DecimalMax(value = "1.0", message = "相似度阈值不能大于1")
        @Schema(
                description = "纯语义候选的最低相似度；命中商品型号或中文关键词时允许由本地重排判定相关",
                example = "0.5"
        )
        Double similarityThreshold
) {

    private static final int DEFAULT_TOP_K = 5;
    /**
     * Qwen3 Embedding 在当前测试知识库中会让通用说明对无关问题得到较高
     * 分数，因此 0.50 是没有任何词法命中时的最低保护线。商品型号或中文
     * 关键词明确命中时，由本地重排器综合判断，避免宽泛购物需求被提前截断。
     */
    private static final double MINIMUM_SIMILARITY_THRESHOLD = 0.50D;

    /** 去除用户输入首尾空白，避免相同问题产生无意义的不同向量。 */
    public String normalizedQuery() {
        return query == null ? null : query.strip();
    }

    public int effectiveTopK() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }

    public double effectiveSimilarityThreshold() {
        return similarityThreshold == null
                ? MINIMUM_SIMILARITY_THRESHOLD
                : Math.max(
                        similarityThreshold,
                        MINIMUM_SIMILARITY_THRESHOLD
                );
    }
}
