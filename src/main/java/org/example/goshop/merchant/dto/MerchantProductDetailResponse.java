package org.example.goshop.merchant.dto;

import org.example.goshop.product.entity.ProductSpu;

import java.util.List;

public record MerchantProductDetailResponse(
        Long id,
        Long categoryId,
        String title,
        String description,
        String mainImage,
        Integer status,
        List<CreateMerchantSkuResponse> skus
) {
    public static MerchantProductDetailResponse from(
            ProductSpu spu,
            List<CreateMerchantSkuResponse> skus
    ) {
        return new MerchantProductDetailResponse(
                spu.getId(),
                spu.getCategoryId(),
                spu.getTitle(),
                spu.getDescription(),
                spu.getMainImage(),
                spu.getStatus(),
                skus
        );
    }
}
