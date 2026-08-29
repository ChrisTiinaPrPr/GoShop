package org.example.goshop.favorite.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Mapper 查询收藏列表时使用的内部投影。
 *
 * <p>商品标题、主图、价格和商家信息都在查询时实时读取，避免收藏表保存一份会过期的商品快照。</p>
 */
@Data
public class FavoriteProductItem {

    private Long productId;
    private Long merchantId;
    private String merchantName;
    private String title;
    private String mainImage;
    private Long minPriceCent;
    private Boolean available;
    private LocalDateTime favoritedAt;
}
