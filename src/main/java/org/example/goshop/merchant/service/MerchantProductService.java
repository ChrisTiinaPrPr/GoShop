package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.*;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.oss.OssStorageService;
import org.example.goshop.oss.OssUploadResult;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductSort;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.entity.ProductCategory;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductCategoryMapper;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSkuResponse;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.crypto.dsig.Reference;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MerchantProductService {

    private final MerchantMapper merchantMapper;
    private final ProductSpuMapper productSpuMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final ProductSkuMapper productSkuMapper;
    private final OssStorageService ossStorageService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ProductDetailCacheService productDetailCacheService;

    public PageResult<MerchantProductListResponse> listCurrentMerchantProducts(
            Long userId,
            long page,
            long pageSize,
            Long categoryId,
            String keyword,
            Integer status,
            String sortValue
    ) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus, 1)
        );

        if (merchant == null) {
            throw new BusinessException(40301,"商家不存在或已停用");
        }

        ProductSort sort = ProductSort.fromApiValue(sortValue);

        String normalizedKeyword = keyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

        MerchantProductListQuery query = new MerchantProductListQuery(merchant.getId(), categoryId, normalizedKeyword, status, sort.name());

        IPage<MerchantProductListItem> productPage = productSpuMapper.selectMerchantProductPage(
                new Page<>(page, pageSize),
                query
        );

        List<MerchantProductListResponse> records = productPage.getRecords().stream().map(MerchantProductListResponse::from).toList();

        return new PageResult<>(
                records,
                productPage.getCurrent(),
                productPage.getSize(),
                productPage.getTotal()
        );
    }

    /** 查询当前商家自己的商品详情，供编辑页面回显 SPU 与完整 SKU 集合。 */
    public MerchantProductDetailResponse getCurrentMerchantProductDetail(Long userId, Long spuId) {
        Merchant merchant = getCurrentEnabledMerchant(userId);
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, spuId)
                        .eq(ProductSpu::getMerchantId, merchant.getId())
        );
        if (spu == null) {
            throw new BusinessException(40401, "商品不存在或无权访问");
        }
        return MerchantProductDetailResponse.from(spu, listSkuResponse(spu.getId()));
    }

    @Transactional
    public CreateMerchantProductResponse createCurrentMerchantProduct(
            Long userId,
            CreateMerchantProductRequest request,
            MultipartFile mainImage
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);
        // 商品只能创建在当前商家自己的启用分类中
        ProductCategory category = productCategoryMapper.selectOne(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getId, request.categoryId())
                        .eq(ProductCategory::getMerchantId, merchant.getId())
                        .eq(ProductCategory::getStatus, 1)
        );
        if (category == null) {
            throw new BusinessException(40401,"商品分类不存在或已停用");
        }

        // 在上传文件前完成 SKU 规格序列化和重复规格校验
        List<String> specsJsonList = request.skus().stream().map(sku -> toCanonicalSpecsJson(sku.specs())).toList();
        Set<String> uniqueSpecs = new HashSet<>(specsJsonList);
        if (specsJsonList.size() != uniqueSpecs.size()) {
            throw new BusinessException(40901,"商品规格重复");
        }

        String uploadedObjectKey = null;

        try {
            OssUploadResult uploadResult = ossStorageService.uploadProductMainImage(
                    merchant.getId(),
                    mainImage
            );
            uploadedObjectKey = uploadResult.objectKey();

            ProductSpu spu = new ProductSpu();
            spu.setMerchantId(merchant.getId());
            spu.setCategoryId(category.getId());
            spu.setTitle(request.title().trim());
            spu.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
            spu.setMainImage(uploadResult.url());
            spu.setMainImageObjectKey(uploadResult.objectKey());

            // 新建商品先下架，必须经状态接口明确上架后才对用户可见
            spu.setStatus(0);
            spu.setSalesCount(0L);

            productSpuMapper.insert(spu);

            List<CreateMerchantSkuResponse> skuResponses = new ArrayList<>();
            for (int index = 0; index < request.skus().size(); index++) {
                CreateMerchantSkuRequest requestSku = request.skus().get(index);

                ProductSku sku = new ProductSku();
                sku.setSpuId(spu.getId());
                sku.setSpecsJson(specsJsonList.get(index));
                sku.setPriceCent(requestSku.priceCent());
                sku.setStock(requestSku.stock());
                sku.setLockedStock(0);
                sku.setVersion(0);
                sku.setStatus(1);

                productSkuMapper.insert(sku);
                skuResponses.add(CreateMerchantSkuResponse.from(sku));
            }

            // 清除该 ID 可能已经存在的空值缓存；只在商品事务提交成功后执行。
            productDetailCacheService.evictAfterCommit(spu.getId());
            return CreateMerchantProductResponse.from(spu, skuResponses);
        } catch (RuntimeException exception) {
            // 数据库事务回滚时，删除刚上传但未被引用的 OSS 主图
            ossStorageService.deleteQuietly(uploadedObjectKey);
            throw exception;
        }
    }

    private Merchant getCurrentEnabledMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus, 1)
        );
        if (merchant == null) {
            throw new BusinessException(40301,"商家不存在或已停用");
        }
        return merchant;
    }

    private String toCanonicalSpecsJson(Map<String, String> specs) {
        boolean hasBlankSpec = specs == null
                || specs.isEmpty()
                || specs.entrySet().stream().anyMatch(entry ->
                !StringUtils.hasText(entry.getKey())
                        || !StringUtils.hasText(entry.getValue())
        );

        if (hasBlankSpec) {
            throw new BusinessException(40001, "SKU 规格名称和值不能为空");
        }

        try {
            return objectMapper.writeValueAsString(new TreeMap<>(specs));
        } catch (JsonProcessingException e) {
            throw new BusinessException(40001, "SKU 规格格式不合法");
        }
    }

    /**
     * 解析 multipart 中的商品 JSON，并执行 DTO 约束校验。
     */
    public CreateMerchantProductRequest parseCreateProductRequest(String productJson) {
        if (!StringUtils.hasText(productJson)) {
            throw new BusinessException(40001, "product 参数不能为空");
        }

        final CreateMerchantProductRequest request;
        try {
            request = objectMapper.readValue(
                    productJson,
                    CreateMerchantProductRequest.class
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(40001, "product 必须是合法的 JSON");
        }

        if (request == null) {
            throw new BusinessException(40001, "product 参数不能为空");
        }


        Set<ConstraintViolation<CreateMerchantProductRequest>> violations =
                validator.validate(request);

        if (!violations.isEmpty()) {
            throw new BusinessException(
                    40001,
                    violations.iterator().next().getMessage()
            );
        }

        return request;
    }

    @Transactional
    public MerchantProductDetailResponse updateCurrentMerchantProduct(
            Long userId,
            Long spuId,
            UpdateMerchantProductRequest request,
            MultipartFile mainImage
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);

        // 同时按商品 ID 与当前商家 ID 查询， 防止越权编辑。
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, spuId)
                        .eq(ProductSpu::getMerchantId, merchant.getId())
        );

        if (spu == null) {
            throw new BusinessException(40401, "商品不存在");
        }

        if (request.title() == null
        && request.description() == null
        && request.status() == null
        && request.categoryId() == null
        && request.skus() == null
        && mainImage == null) {
            throw new BusinessException(40001, "请至少填写一个字段");
        }

        String oldObjectKey = spu.getMainImageObjectKey();
        String newObjectKey = null;

        try {
            if (request.title() != null) {
                if (!StringUtils.hasText(request.title())) {
                    throw new BusinessException(40001, "商品标题不能为空");
                }
                spu.setTitle(request.title().trim());
            }

            if (request.description() != null) {
                // 空字符串表示清空描述
                spu.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);

            }

            if (request.categoryId() != null) {
                ProductCategory category = productCategoryMapper.selectOne(
                        new LambdaQueryWrapper<ProductCategory>()
                                .eq(ProductCategory::getId, request.categoryId())
                                .eq(ProductCategory::getMerchantId, merchant.getId())
                                .eq(ProductCategory::getStatus, 1)
                );
                if (category == null) {
                    throw new BusinessException(40001, "商品分类不存在或已停用");
                }
                spu.setCategoryId(category.getId());
            }

            if(request.status() != null) {
                spu.setStatus(request.status());
            }

            if (mainImage != null) {
                OssUploadResult uploadResult = ossStorageService.uploadProductMainImage(merchant.getId(), mainImage);
                newObjectKey = uploadResult.objectKey();
                spu.setMainImage(uploadResult.url());
                spu.setMainImageObjectKey(newObjectKey);
            }

            productSpuMapper.updateById(spu);

            // skus 不传代表不修改 SKU；传入则按完整集合同步
            if (request.skus() != null) {
                replaceProductSkus(spu.getId(),request.skus());
            }

            // 新图片已随事务提交后，再删除旧图，避免回滚时丢失原图
            if (newObjectKey != null && StringUtils.hasText(oldObjectKey)) {
                String finalObjectKey = oldObjectKey;
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                ossStorageService.deleteQuietly(finalObjectKey);
                            }
                        }
                );
            }
            // 标题、图片、上下架状态、价格、规格或库存都可能进入公开详情缓存。
            productDetailCacheService.evictAfterCommit(spu.getId());
            return MerchantProductDetailResponse.from(spu,listSkuResponse(spu.getId()));
        } catch (RuntimeException e) {
            // 数据库失败时，清理本次刚上传但未被引用的新图。
            ossStorageService.deleteQuietly(newObjectKey);
            throw e;
        }
    }

    private void replaceProductSkus(Long spuId, List<UpdateMerchantSkuRequest> requests) {
        if (requests.isEmpty()) {
            throw new BusinessException(40001, "商品 SKU 不能为空");
        }
        List<ProductSku> existingSkus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getSpuId, spuId)
        );

        Map<Long,ProductSku> existingSkuMap = existingSkus.stream().collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        Set<Long> submittedIds = new HashSet<>();
        List<String> specsJsonList = new ArrayList<>();

        // 先完成全部校验，避免校验到一半时才更新数据库。
        for (UpdateMerchantSkuRequest request : requests) {
            if (request.id() != null && !submittedIds.add(request.id())) {
                throw new BusinessException(40001, "商品 SKU ID 不能重复");
            }

            specsJsonList.add(toCanonicalSpecsJson(request.specs()));
        }

        if (new HashSet<>(specsJsonList).size() != specsJsonList.size()) {
            throw new BusinessException(40901,"商品规格重复");
        }

        for (int index = 0; index < requests.size(); index ++) {
            UpdateMerchantSkuRequest request = requests.get(index);

            if (request.id() == null) {
                ProductSku sku = new ProductSku();
                sku.setSpuId(spuId);
                sku.setSpecsJson(specsJsonList.get(index));
                sku.setPriceCent(request.priceCent());
                sku.setStock(request.stock());
                sku.setLockedStock(0);
                sku.setVersion(0);
                sku.setStatus(request.status() == null ? 1 : request.status());

                productSkuMapper.insert(sku);
                continue;
            }

            ProductSku sku = existingSkuMap.get(request.id());
            if (sku == null) {
                // SKU 不存在、属于其他商品都会返回此结果，避免越权探测。
                throw new BusinessException(40401, "商品 SKU 不存在或已删除");
            }

            int lockedStock = sku.getLockedStock() == null ? 0 : sku.getLockedStock();
            if (request.stock() < lockedStock) {
                throw new BusinessException(40001, "商品 SKU 库存不能小于锁定库存");
            }

            sku.setSpecsJson(specsJsonList.get(index));
            sku.setPriceCent(request.priceCent());
            sku.setStock(request.stock());

            if (request.status() != null) {
                sku.setStatus(request.status());
            }

            // 库存被人工修改时递增版本，供后续下单扣库存时做并发控制
            sku.setVersion((sku.getVersion() == null ? 0 : sku.getVersion()) + 1);

            productSkuMapper.updateById(sku);
        }

        // 本次提交未携带的旧 SKU 仅下架，不物理删除，历史订单仍能保存 SKU 快照。
        for (ProductSku sku : existingSkus) {
            if (!submittedIds.contains(sku.getId()) && sku.getStatus() != 0) {
                sku.setStatus(0);
                productSkuMapper.updateById(sku);
            }
        }
    }
    private List<CreateMerchantSkuResponse> listSkuResponse(Long spuId) {
        return productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getSpuId, spuId)
                        .orderByAsc(ProductSku::getId)
        ).stream().map(CreateMerchantSkuResponse::from).toList();
    }

    public UpdateMerchantProductRequest parseUpdateProductRequest(String productJson) {
        if (!StringUtils.hasText(productJson)) {
            throw new BusinessException(40001, "product 参数不能为空");
        }

        final UpdateMerchantProductRequest request;
        try {
            request = objectMapper.readValue(
                    productJson,
                    UpdateMerchantProductRequest.class
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(40001, "product 必须是合法的 JSON");
        }

        if (request == null) {
            throw new BusinessException(40001, "product 参数不能为空");
        }

        Set<ConstraintViolation<UpdateMerchantProductRequest>> violations =
                validator.validate(request);

        if (!violations.isEmpty()) {
            throw new BusinessException(
                    40001,
                    violations.iterator().next().getMessage()
            );
        }

        return request;
    }
    @Transactional
    public MerchantProductStatusResponse updateCurrentMerchantProductStatus(
            Long userId,
            Long spuId,
            UpdateMerchantProductStatusRequest request
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);

        // 商品 ID 和当前商家 ID 必须同时匹配，防止越权上下架
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, spuId)
                        .eq(ProductSpu::getMerchantId, merchant.getId())
        );
        if (spu == null) {
            throw new BusinessException(40401, "商品不存在或已删除");
        }
        if (request.status() == 1) {
            validateProductCanBePublished(spu,merchant.getId());
        }

        spu.setStatus(request.status());
        productSpuMapper.updateById(spu);
        productDetailCacheService.evictAfterCommit(spu.getId());
        return MerchantProductStatusResponse.from(spu);
    }

    /**
     * 避免出现已上架但消费者不可购买的无效商品
     */
    private void validateProductCanBePublished(ProductSpu spu, Long merchantId) {
        ProductCategory category = productCategoryMapper.selectOne(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getId, spu.getCategoryId())
                        .eq(ProductCategory::getMerchantId, merchantId)
                        .eq(ProductCategory::getStatus, 1)
        );
        if (category == null) {
            throw new BusinessException(40901, "商品所属分类不存在或已禁用，不能上架");
        }

        Long enabledSkuCount = productSkuMapper.selectCount(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getSpuId, spu.getId())
                        .eq(ProductSku::getStatus, 1)
        );

        if (enabledSkuCount == null || enabledSkuCount == 0) {
            throw new BusinessException(40901, "至少保留一个已启用 SKU 后才能上架");
        }
    }
}
