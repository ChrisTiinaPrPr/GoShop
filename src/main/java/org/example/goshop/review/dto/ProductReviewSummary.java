package org.example.goshop.review.dto;

import lombok.Data;

/**
 * 商品公开评价统计的 Mapper 投影。
 */
@Data
public class ProductReviewSummary {

    private Long reviewCount;
    private Double averageScore;
}
