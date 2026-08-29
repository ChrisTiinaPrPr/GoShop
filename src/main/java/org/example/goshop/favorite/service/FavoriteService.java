package org.example.goshop.favorite.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.favorite.dto.FavoriteProductItem;
import org.example.goshop.favorite.dto.FavoriteProductResponse;
import org.example.goshop.favorite.dto.FavoriteStatusResponse;
import org.example.goshop.favorite.mapper.ProductFavoriteMapper;
import org.example.goshop.product.dto.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final ProductFavoriteMapper favoriteMapper;

    /**
     * 收藏一个当前公开可购买的商品。
     *
     * <p>商品不存在、SPU 已下架或没有启用 SKU 时统一返回“商品不存在或已下架”，
     * 避免向买家暴露不可见商品的内部状态。重复调用由数据库唯一键保证幂等。</p>
     */
    @Transactional
    public void addFavorite(Long userId, Long productId) {
        if (!favoriteMapper.existsPublicProduct(productId)) {
            throw new BusinessException(40401, "商品不存在或已下架");
        }

        favoriteMapper.insertIgnore(IdWorker.getId(), userId, productId);
    }

    /**
     * 取消当前用户对指定商品的收藏。
     *
     * <p>删除条件始终包含 userId，防止越权删除其他买家的收藏；记录不存在时也返回成功。</p>
     */
    @Transactional
    public void removeFavorite(Long userId, Long productId) {
        favoriteMapper.deleteByUserAndProduct(userId, productId);
    }

    @Transactional(readOnly = true)
    public FavoriteStatusResponse getFavoriteStatus(Long userId, Long productId) {
        boolean favorited = favoriteMapper.countByUserAndProduct(userId, productId) > 0;
        return new FavoriteStatusResponse(productId, favorited);
    }

    @Transactional(readOnly = true)
    public PageResult<FavoriteProductResponse> listFavorites(
            Long userId,
            long page,
            long pageSize
    ) {
        IPage<FavoriteProductItem> favoritePage =
                favoriteMapper.selectFavoriteProductPage(
                        new Page<>(page, pageSize),
                        userId
                );

        List<FavoriteProductResponse> records = favoritePage.getRecords()
                .stream()
                .map(FavoriteProductResponse::from)
                .toList();

        return new PageResult<>(
                records,
                favoritePage.getCurrent(),
                favoritePage.getSize(),
                favoritePage.getTotal()
        );
    }
}
