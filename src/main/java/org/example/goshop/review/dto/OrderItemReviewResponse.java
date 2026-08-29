package org.example.goshop.review.dto;

import java.time.LocalDateTime;

/**
 * 当前买家订单中的单个商品评价状态。
 */
public record OrderItemReviewResponse(
        Long orderItemId,
        Long productId,
        Long skuId,
        String productTitle,
        String productImage,
        String specsJson,
        boolean reviewed,
        boolean reviewable,
        Integer score,
        String content,
        LocalDateTime reviewedAt
) {

    public static OrderItemReviewResponse from(
            OrderItemReviewItem item,
            boolean orderCompleted
    ) {
        boolean reviewed = item.getReviewId() != null;
        return new OrderItemReviewResponse(
                item.getOrderItemId(),
                item.getSpuId(),
                item.getSkuId(),
                item.getProductTitle(),
                item.getProductImage(),
                item.getSpecsJson(),
                reviewed,
                orderCompleted && !reviewed,
                item.getScore(),
                item.getContent(),
                item.getReviewedAt()
        );
    }
}
