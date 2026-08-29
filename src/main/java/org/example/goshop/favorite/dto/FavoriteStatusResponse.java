package org.example.goshop.favorite.dto;

/**
 * 单个商品的当前用户收藏状态。
 */
public record FavoriteStatusResponse(
        Long productId,
        boolean favorited
) {
}
