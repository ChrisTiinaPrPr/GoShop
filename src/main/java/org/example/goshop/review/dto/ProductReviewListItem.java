package org.example.goshop.review.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品公开评价列表的 Mapper 投影。
 */
@Data
public class ProductReviewListItem {

    private Long id;
    private Integer score;
    private String content;
    private String reviewerNickname;
    private String reviewerAvatarUrl;
    private String specsJson;
    private LocalDateTime createdAt;
}
