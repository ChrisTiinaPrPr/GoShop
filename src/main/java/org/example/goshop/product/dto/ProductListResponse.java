package org.example.goshop.product.dto;

public record ProductListResponse(
        Long id,
        Long merchantId,
        Long categoryId,
        String title,
        String mainImage,
        Long minPriceCent,
        Long salesCount
) {
    public static ProductListResponse from(ProductListItem item) {
        return new ProductListResponse(
                item.getId(),
                item.getMerchantId(),
                item.getCategoryId(),
                item.getTitle(),
                item.getMainImage(),
                item.getMinPriceCent(),
                item.getSalesCount()
        );
    }
}
