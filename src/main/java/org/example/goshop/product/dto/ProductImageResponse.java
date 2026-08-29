package org.example.goshop.product.dto;

import org.example.goshop.product.entity.ProductImage;

public record ProductImageResponse(
        Long id,
        String url,
        Integer sort
) {
    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(
                image.getId(),
                image.getUrl(),
                image.getSort()
        );
    }
}
