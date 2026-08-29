package org.example.goshop.review.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本人订单评价状态查询的 Mapper 投影，包含订单商品快照和可选评价。
 */
@Data
public class OrderItemReviewItem {

    private Long orderItemId;
    private Long spuId;
    private Long skuId;
    private String productTitle;
    private String productImage;
    private String specsJson;
    private Long reviewId;
    private Integer score;
    private String content;
    private LocalDateTime reviewedAt;
}
