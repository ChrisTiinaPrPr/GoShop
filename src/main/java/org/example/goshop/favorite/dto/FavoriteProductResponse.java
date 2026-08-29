package org.example.goshop.favorite.dto;

import java.time.LocalDateTime;

/**
 * 收藏商品列表项。
 *
 * @param productId    商品 SPU ID
 * @param merchantId   当前所属商家 ID
 * @param merchantName 当前店铺名称
 * @param title        当前商品标题
 * @param mainImage    当前商品主图
 * @param minPriceCent 当前启用 SKU 的最低价，全部 SKU 停用时为 null
 * @param available    当前是否仍可从公开商品入口购买
 * @param favoritedAt  收藏时间
 */
public record FavoriteProductResponse(
        Long productId,
        Long merchantId,
        String merchantName,
        String title,
        String mainImage,
        Long minPriceCent,
        boolean available,
        LocalDateTime favoritedAt
) {

    public static FavoriteProductResponse from(FavoriteProductItem item) {
        return new FavoriteProductResponse(
                item.getProductId(),
                item.getMerchantId(),
                item.getMerchantName(),
                item.getTitle(),
                item.getMainImage(),
                item.getMinPriceCent(),
                Boolean.TRUE.equals(item.getAvailable()),
                item.getFavoritedAt()
        );
    }
}
