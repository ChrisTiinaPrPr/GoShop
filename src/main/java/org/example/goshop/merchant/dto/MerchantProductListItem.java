package org.example.goshop.merchant.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MerchantProductListItem {
    private Long id;
    private Long categoryId;
    private String title;
    private String mainImage;

    // 0:下架；1:在售
    private Integer status;
    private Long salesCount;

    // 聚合当前所有商品 sku， 用于商家后台展示价格区间
    private Long minPriceCent;
    private Long maxPriceCent;
    private Long skuCount;

    private LocalDateTime createdAt;
}
