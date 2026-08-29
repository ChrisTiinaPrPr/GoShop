package org.example.goshop.favorite.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.favorite.dto.FavoriteProductItem;
import org.example.goshop.favorite.dto.FavoriteStatusResponse;
import org.example.goshop.favorite.mapper.ProductFavoriteMapper;
import org.example.goshop.product.dto.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏业务单元测试，重点覆盖幂等、商品可见性和用户数据隔离参数。
 */
class FavoriteServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long PRODUCT_ID = 2001L;

    private ProductFavoriteMapper favoriteMapper;
    private FavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        favoriteMapper = mock(ProductFavoriteMapper.class);
        favoriteService = new FavoriteService(favoriteMapper);
    }

    /**
     * 可见商品应写入收藏；Mapper 的 INSERT IGNORE 使重复调用即使影响零行也保持成功。
     */
    @Test
    void shouldAddPublicProductIdempotently() {
        when(favoriteMapper.existsPublicProduct(PRODUCT_ID)).thenReturn(true);
        when(favoriteMapper.insertIgnore(anyLong(), eq(USER_ID), eq(PRODUCT_ID)))
                .thenReturn(0);

        assertDoesNotThrow(() -> favoriteService.addFavorite(USER_ID, PRODUCT_ID));

        verify(favoriteMapper).insertIgnore(anyLong(), eq(USER_ID), eq(PRODUCT_ID));
    }

    /**
     * 下架、不存在或没有启用 SKU 的商品都不能新建收藏。
     */
    @Test
    void shouldRejectInvisibleProduct() {
        when(favoriteMapper.existsPublicProduct(PRODUCT_ID)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> favoriteService.addFavorite(USER_ID, PRODUCT_ID)
        );

        assertEquals(40401, exception.getCode());
        assertEquals("商品不存在或已下架", exception.getMessage());
        verify(favoriteMapper, never()).insertIgnore(anyLong(), anyLong(), anyLong());
    }

    /**
     * 取消收藏只使用 JWT 用户 ID 与商品 ID 作为联合删除条件，不存在也应正常返回。
     */
    @Test
    void shouldRemoveOnlyCurrentUserFavoriteIdempotently() {
        when(favoriteMapper.deleteByUserAndProduct(USER_ID, PRODUCT_ID)).thenReturn(0);

        assertDoesNotThrow(() -> favoriteService.removeFavorite(USER_ID, PRODUCT_ID));

        verify(favoriteMapper).deleteByUserAndProduct(USER_ID, PRODUCT_ID);
    }

    @Test
    void shouldReturnCurrentFavoriteStatus() {
        when(favoriteMapper.countByUserAndProduct(USER_ID, PRODUCT_ID)).thenReturn(1L);

        FavoriteStatusResponse response =
                favoriteService.getFavoriteStatus(USER_ID, PRODUCT_ID);

        assertEquals(PRODUCT_ID, response.productId());
        assertTrue(response.favorited());
    }

    /**
     * 列表必须透传分页元数据，并把已下架商品保留为 available=false。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldListFavoritesIncludingUnavailableProducts() {
        LocalDateTime favoritedAt = LocalDateTime.of(2026, 8, 10, 12, 0);
        FavoriteProductItem item = new FavoriteProductItem();
        item.setProductId(PRODUCT_ID);
        item.setMerchantId(3001L);
        item.setMerchantName("示例店铺");
        item.setTitle("已下架商品");
        item.setMainImage("https://example.test/product.jpg");
        item.setMinPriceCent(null);
        item.setAvailable(false);
        item.setFavoritedAt(favoritedAt);

        Page<FavoriteProductItem> mapperPage = new Page<>(2, 10, 11);
        mapperPage.setRecords(List.of(item));
        when(favoriteMapper.selectFavoriteProductPage(
                org.mockito.ArgumentMatchers.any(Page.class),
                eq(USER_ID)
        )).thenReturn(mapperPage);

        PageResult<org.example.goshop.favorite.dto.FavoriteProductResponse> result =
                favoriteService.listFavorites(USER_ID, 2, 10);

        assertEquals(2, result.page());
        assertEquals(10, result.pageSize());
        assertEquals(11, result.total());
        assertEquals(1, result.records().size());
        assertFalse(result.records().get(0).available());
        assertNull(result.records().get(0).minPriceCent());
        assertEquals(favoritedAt, result.records().get(0).favoritedAt());

        ArgumentCaptor<Page<FavoriteProductItem>> pageCaptor =
                ArgumentCaptor.forClass(Page.class);
        verify(favoriteMapper).selectFavoriteProductPage(pageCaptor.capture(), eq(USER_ID));
        assertEquals(2, pageCaptor.getValue().getCurrent());
        assertEquals(10, pageCaptor.getValue().getSize());
    }
}
