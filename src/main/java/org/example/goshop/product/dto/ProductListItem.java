package org.example.goshop.product.dto;

import lombok.Data;

@Data
public class ProductListItem {

    private Long id;
    private Long merchantId;
    private Long categoryId;
    private String title;
    private String mainImage;
    private Long minPriceCent;
    private Long salesCount;
}
