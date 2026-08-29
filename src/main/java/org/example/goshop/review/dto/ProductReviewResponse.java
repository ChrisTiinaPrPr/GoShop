package org.example.goshop.review.dto;

import org.example.goshop.review.entity.ProductReview;

import java.time.LocalDateTime;

/**
 * 创建评价后的响应。
 */
public record ProductReviewResponse(
        Long id,
        Long orderItemId,
        Long productId,
        Integer score,
        String content,
        LocalDateTime createdAt
) {

    public static ProductReviewResponse from(ProductReview review) {
        return new ProductReviewResponse(
                review.getId(),
                review.getOrderItemId(),
                review.getSpuId(),
                review.getScore(),
                review.getContent(),
                review.getCreatedAt()
        );
    }
}
