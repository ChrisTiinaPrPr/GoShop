package org.example.goshop.review.dto;

import java.time.LocalDateTime;

/**
 * 面向所有访客公开的已购商品评价。
 */
public record PublicProductReviewResponse(
        Long id,
        Integer score,
        String content,
        String reviewerNickname,
        String reviewerAvatarUrl,
        String specsJson,
        LocalDateTime createdAt
) {

    public static PublicProductReviewResponse from(ProductReviewListItem item) {
        return new PublicProductReviewResponse(
                item.getId(),
                item.getScore(),
                item.getContent(),
                item.getReviewerNickname(),
                item.getReviewerAvatarUrl(),
                item.getSpecsJson(),
                item.getCreatedAt()
        );
    }
}
