package org.example.goshop.merchant.dto;

import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;

import java.util.List;

public record CreateMerchantProductResponse(
        Long id,
        Long categoryId,
        String title,
        String mainImage,
        Integer status,
        List<CreateMerchantSkuResponse> skus
) {
    public static CreateMerchantProductResponse from(ProductSpu spu,List<CreateMerchantSkuResponse> skus) {
        return new CreateMerchantProductResponse(
                spu.getId(),
                spu.getCategoryId(),
                spu.getTitle(),
                spu.getMainImage(),
                spu.getStatus(),
                skus
        );
    }
}
