package org.example.goshop.product.mapper;

import org.example.goshop.product.dto.ProductImageResponse;
import org.example.goshop.product.entity.ProductSpu;

import java.util.List;

public record ProductDetailResponse(
        Long id,
        Long merchantId,
        Long categoryId,
        String title,
        String description,
        String mainImage,
        Long salesCount,
        List<ProductSkuResponse> skus,

        // 附加图片， 不包含 mainImage，前端可将 mainImage 固定展示在第一张
        List<ProductImageResponse> images
) {
    public static ProductDetailResponse from(ProductSpu spu, List<ProductSkuResponse> skus, List<ProductImageResponse> images) {
        return new ProductDetailResponse(
                spu.getId(),
                spu.getMerchantId(),
                spu.getCategoryId(),
                spu.getTitle(),
                spu.getDescription(),
                spu.getMainImage(),
                spu.getSalesCount(),
                skus,
                images

        );
    }
}
