package org.example.goshop.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.service.MerchantService;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.dto.*;
import org.example.goshop.product.entity.ProductImage;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductSpuMapper productSpuMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductImageMapper productImageMapper;
    private final ProductDetailCacheService productDetailCacheService;
    private final MerchantService merchantService;

    public PageResult<ProductListResponse> listPublicProducts(
            long page,
            long pageSize,
            Long categoryId,
            String keyword,
            String sortValue
    ) {
        ProductSort sort = ProductSort.fromApiValue(sortValue);

        // 空白关键词不参与 SQL 查询条件
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

        ProductListQuery query = new ProductListQuery(
                null,
                categoryId,
                normalizedKeyword,
                null,
                null,
                sort.name()
        );

        IPage<ProductListItem> productPage = productSpuMapper.selectPublicProductPage(
                new Page<>(page, pageSize),
                query
        );

        List<ProductListResponse> records = productPage.getRecords().stream().map(ProductListResponse::from).toList();

        return new PageResult<>(
                records,
                productPage.getCurrent(),
                productPage.getSize(),
                productPage.getTotal()
        );
    }

    /**
     * 分页查询指定店铺的公开可售商品。
     *
     * <p>查询前先验证商家处于启用状态。真正的租户边界仍写入
     * ProductListQuery，并由 Mapper 在 SQL 层使用 merchant_id 过滤，
     * 不能在查询出全平台商品后再由 Java 过滤。</p>
     *
     * <p>其余可见性规则与平台商品列表完全一致：只返回已上架 SPU，
     * 且商品至少存在一个启用 SKU。</p>
     */
    public PageResult<ProductListResponse>
    listPublicMerchantProducts(
            Long merchantId,
            long page,
            long pageSize,
            Long categoryId,
            String keyword,
            String sortValue
    ) {
        /*
         * 不能只依赖商品表中的 merchant_id。商家被停用后，即使其商品
         * 状态尚未来得及批量下架，店铺公开接口也必须立即不可访问。
         */
        merchantService.requireEnabledMerchant(merchantId);

        ProductSort sort = ProductSort.fromApiValue(
                sortValue
        );

        String normalizedKeyword =
                StringUtils.hasText(keyword)
                        ? keyword.trim()
                        : null;

        ProductListQuery query = new ProductListQuery(
                merchantId,
                categoryId,
                normalizedKeyword,
                null,
                null,
                sort.name()
        );

        IPage<ProductListItem> productPage =
                productSpuMapper.selectPublicProductPage(
                        new Page<>(page, pageSize),
                        query
                );

        List<ProductListResponse> records =
                productPage.getRecords()
                        .stream()
                        .map(ProductListResponse::from)
                        .toList();

        return new PageResult<>(
                records,
                productPage.getCurrent(),
                productPage.getSize(),
                productPage.getTotal()
        );
    }

    /**
     * 为购物 Agent 查询公开可售商品。
     *
     * <p>该方法仍然属于商品业务 Service。Agent 工具只能调用此方法，
     * 不能直接调用 ProductSpuMapper，从而确保普通商品接口和 Agent
     * 使用相同的上架状态、SKU 状态及排序规则。</p>
     *
     * @param keyword      商品标题关键词，可为空
     * @param categoryId   分类 ID，可为空
     * @param minPriceCent 最低价格，单位分，可为空
     * @param maxPriceCent 最高价格，单位分，可为空
     * @param sort         固定排序枚举，可为空；为空时使用最新排序
     * @param limit        返回数量，只允许 1～10
     */
    public PageResult<ProductListResponse>
    searchPublicProductsForAgent(
            String keyword,
            Long categoryId,
            Long minPriceCent,
            Long maxPriceCent,
            ProductSort sort,
            int limit
    ) {
        /*
         * Agent 的返回数量必须由服务端限制。
         * 不能信任模型传入的 limit，避免一次把大量商品放进模型上下文。
         */
        if (limit < 1 || limit > 10) {
            throw new BusinessException(
                    40001,
                    "Agent 商品查询数量必须在 1～10 之间"
            );
        }

        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException(
                    40001,
                    "商品分类 ID 必须为正数"
            );
        }

        if (minPriceCent != null && minPriceCent < 0) {
            throw new BusinessException(
                    40001,
                    "最低价格不能小于 0"
            );
        }

        if (maxPriceCent != null && maxPriceCent < 0) {
            throw new BusinessException(
                    40001,
                    "最高价格不能小于 0"
            );
        }

        if (minPriceCent != null
                && maxPriceCent != null
                && minPriceCent > maxPriceCent) {
            throw new BusinessException(
                    40001,
                    "最低价格不能高于最高价格"
            );
        }

        /*
         * 空白关键词不参与 SQL。
         * 同时限制关键词长度，避免模型产生异常长的模糊查询。
         */
        String normalizedKeyword =
                StringUtils.hasText(keyword)
                        ? keyword.strip()
                        : null;

        if (normalizedKeyword != null
                && normalizedKeyword.length() > 100) {
            throw new BusinessException(
                    40001,
                    "商品搜索关键词不能超过 100 个字符"
            );
        }

        ProductSort effectiveSort =
                sort == null
                        ? ProductSort.LATEST
                        : sort;

        ProductListQuery query =
                new ProductListQuery(
                        null,
                        categoryId,
                        normalizedKeyword,
                        minPriceCent,
                        maxPriceCent,
                        effectiveSort.name()
                );

        /*
         * Agent 首期只取第一页，最大十件商品。
         * MyBatis-Plus 仍执行 count 查询，方便工具告诉模型是否还有更多结果。
         */
        IPage<ProductListItem> productPage =
                productSpuMapper.selectPublicProductPage(
                        new Page<>(1, limit),
                        query
                );

        List<ProductListResponse> records =
                productPage.getRecords()
                        .stream()
                        .map(ProductListResponse::from)
                        .toList();

        return new PageResult<>(
                records,
                productPage.getCurrent(),
                productPage.getSize(),
                productPage.getTotal()
        );
    }

    public ProductDetailResponse getPublicProductDetail(Long productId) {
        ProductDetailResponse detail = productDetailCacheService.getOrLoad(
                productId,
                () -> loadPublicProductDetailFromDatabase(productId)
        );

        if (detail == null) {
            throw new BusinessException(40401, "商品不存在或已下架");
        }
        return detail;
    }

    /**
     * Cache Aside 的数据库加载函数。
     *
     * <p>返回 null 统一表示商品不可公开访问，缓存层会把该结果写成短 TTL 空值标记。</p>
     */
    private ProductDetailResponse loadPublicProductDetailFromDatabase(Long productId) {
        // 公开接口只允许读取已上架的商品
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, productId)
                        .eq(ProductSpu::getStatus, 1)
        );

        // 对不存在和已下架的商品，统一返回，避免暴露下架商品信息
        if (spu == null) {
            return null;
        }

        // 仅返回启用 SKU，并按价格升序，方便前端默认选择最低价规格
        List<ProductSkuResponse> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getSpuId, productId)
                        .eq(ProductSku::getStatus, 1)
                        .orderByAsc(ProductSku::getPriceCent)
                        .orderByAsc(ProductSku::getId)
        )
                .stream()
                .map(ProductSkuResponse::from)
                .toList();

        // 与商品列表保持一致：没有可用 SKU 的 SPU 不对用户展示
        if (skus.isEmpty()) {
            return null;
        }

        List<ProductImageResponse> images = productImageMapper.selectList(
                new LambdaQueryWrapper<ProductImage>()
                        .eq(ProductImage::getSpuId, productId)
                        .orderByAsc(ProductImage::getSort)
                        .orderByAsc(ProductImage::getId)
        ).stream().map(ProductImageResponse::from).toList();

        return ProductDetailResponse.from(spu, skus, images);
    }
}
