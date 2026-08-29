package org.example.goshop.cart.dto;

public record CartItemResponse(
        Long skuId,
        Long spuId,
        Long merchantId,
        String title,
        String mainImage,
        String specsJson,
        Long priceCent,
        Integer quantity,
        Boolean selected,
        Integer availableStock,
        Boolean valid,
        String status
) {
}
