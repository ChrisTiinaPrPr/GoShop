package org.example.goshop.merchant.dto;

import org.example.goshop.product.entity.ProductImage;

public record MerchantProductImageResponse(
        Long id,
        Long spuId,
        String objectKey,
        String url,
        Integer sort
) {
    public static MerchantProductImageResponse from(ProductImage image) {
        return new MerchantProductImageResponse(
                image.getId(),
                image.getSpuId(),
                image.getObjectKey(),
                image.getUrl(),
                image.getSort()
        );
    }
}
