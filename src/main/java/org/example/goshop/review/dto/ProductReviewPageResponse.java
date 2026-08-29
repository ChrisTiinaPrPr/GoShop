package org.example.goshop.review.dto;

import java.util.List;

/**
 * 商品评价分页及总评分摘要。
 */
public record ProductReviewPageResponse(
        List<PublicProductReviewResponse> records,
        long page,
        long pageSize,
        long total,
        double averageScore
) {
}
