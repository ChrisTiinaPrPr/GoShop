package org.example.goshop.merchant.dto;

public record MerchantProductListResponse(
        Long id,
        Long categoryId,
        String title,
        String mainImage,
        Integer status,
        Long salesCount,
        Long minPriceCent,
        Long maxPriceCent,
        Long skuCount,
        String createdAt
) {
    public static MerchantProductListResponse from(MerchantProductListItem item) {
        return new MerchantProductListResponse(
                item.getId(),
                item.getCategoryId(),
                item.getTitle(),
                item.getMainImage(),
                item.getStatus(),
                item.getSalesCount(),
                item.getMinPriceCent(),
                item.getMaxPriceCent(),
                item.getSkuCount(),
                item.getCreatedAt().toString()
        );
    }
}
