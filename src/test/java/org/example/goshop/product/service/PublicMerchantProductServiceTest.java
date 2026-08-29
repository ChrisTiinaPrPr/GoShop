package org.example.goshop.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.service.MerchantService;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListItem;
import org.example.goshop.product.dto.ProductListQuery;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.mapper.ProductImageMapper;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店铺公开商品查询的租户边界测试。
 *
 * <p>这里不连接真实数据库，重点验证 merchantId 会被写入 Mapper 查询，
 * 并且停用店铺会在执行商品 SQL 前终止。</p>
 */
@ExtendWith(MockitoExtension.class)
class PublicMerchantProductServiceTest {

    @Mock
    private ProductSpuMapper productSpuMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductImageMapper productImageMapper;

    @Mock
    private ProductDetailCacheService
            productDetailCacheService;

    @Mock
    private MerchantService merchantService;

    @InjectMocks
    private ProductService productService;

    @Test
    void shouldQueryOnlyEnabledMerchantProducts() {
        long merchantId = 7001L;

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setStatus(1);

        when(merchantService.requireEnabledMerchant(
                merchantId
        )).thenReturn(merchant);

        ProductListItem item = new ProductListItem();
        item.setId(9001L);
        item.setMerchantId(merchantId);
        item.setCategoryId(3001L);
        item.setTitle("静音机械键盘");
        item.setMinPriceCent(29900L);
        item.setSalesCount(25L);

        Page<ProductListItem> mapperPage =
                new Page<>(2, 12, 1);
        mapperPage.setRecords(List.of(item));

        when(productSpuMapper.selectPublicProductPage(
                any(),
                any(ProductListQuery.class)
        )).thenReturn(mapperPage);

        PageResult<ProductListResponse> result =
                productService.listPublicMerchantProducts(
                        merchantId,
                        2,
                        12,
                        3001L,
                        "  键盘  ",
                        "sales"
                );

        ArgumentCaptor<ProductListQuery> queryCaptor =
                ArgumentCaptor.forClass(
                        ProductListQuery.class
                );

        verify(productSpuMapper)
                .selectPublicProductPage(
                        any(),
                        queryCaptor.capture()
                );

        ProductListQuery query = queryCaptor.getValue();

        assertEquals(merchantId, query.merchantId());
        assertEquals(3001L, query.categoryId());
        assertEquals("键盘", query.keyword());
        assertEquals("SALES", query.sort());
        assertNull(query.minPriceCent());
        assertNull(query.maxPriceCent());

        assertEquals(1, result.records().size());
        assertEquals(merchantId,
                result.records().get(0).merchantId());
        assertEquals(2, result.page());
        assertEquals(12, result.pageSize());
        assertEquals(1, result.total());
    }

    @Test
    void shouldStopBeforeProductQueryWhenMerchantDisabled() {
        long merchantId = 7002L;

        when(merchantService.requireEnabledMerchant(
                merchantId
        )).thenThrow(
                new BusinessException(
                        40401,
                        "商家不存在或已停用"
                )
        );

        assertThrows(
                BusinessException.class,
                () -> productService
                        .listPublicMerchantProducts(
                                merchantId,
                                1,
                                20,
                                null,
                                null,
                                "latest"
                        )
        );

        verify(productSpuMapper, never())
                .selectPublicProductPage(
                        any(),
                        any(ProductListQuery.class)
                );
    }
}
