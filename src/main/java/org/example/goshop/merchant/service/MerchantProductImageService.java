package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.MerchantProductImageResponse;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.oss.OssStorageService;
import org.example.goshop.oss.OssUploadResult;
import org.example.goshop.product.entity.ProductImage;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductImageMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@AllArgsConstructor
public class MerchantProductImageService {

    private static final int MAX_PRODUCT_IMAGES = 9;

    private final MerchantMapper merchantMapper;
    private final ProductSpuMapper productSpuMapper;
    private final ProductImageMapper productImageMapper;
    private final OssStorageService ossStorageService;

    @Transactional
    public MerchantProductImageResponse uploadCurrentMerchantProductImage(
            Long userId,
            Long spuId,
            Integer sort,
            MultipartFile file
    ) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus, 1)
        );

        if (merchant == null) {
            throw new BusinessException(40301,"商家不存在或已停用");
        }

        // 商品必须属于当前商家，避免越权给其他商家商品上传图片。
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, spuId)
                        .eq(ProductSpu::getMerchantId, merchant.getId())
        );

        if (spu == null) {
            throw new BusinessException(40401, "商品不存在");
        }

        Long imageCount = productImageMapper.selectCount(
                new LambdaQueryWrapper<ProductImage>()
                        .eq(ProductImage::getSpuId, spuId)
        );

        if (imageCount != null && imageCount >= MAX_PRODUCT_IMAGES) {
            throw new BusinessException(40001, "每个商品最多上传 9 张图片");
        }

        String uploadedObjectKey = null;
        try {
            OssUploadResult uploadResult = ossStorageService.uploadProductDetailImage(merchant.getId(),file);
            uploadedObjectKey = uploadResult.objectKey();

            ProductImage image = new ProductImage();
            image.setSpuId(spuId);
            image.setObjectKey(uploadResult.objectKey());
            image.setUrl(uploadResult.url());
            image.setSort(sort == null ? 0 : sort);

            productImageMapper.insert(image);
            return MerchantProductImageResponse.from(image);
        } catch (RuntimeException e) {
            // 数据库插入失败时，删除本次刚上传的 OSS 文件。
            ossStorageService.deleteQuietly(uploadedObjectKey);
            throw e;
        }
    }

    @Transactional
    public void deleteCurrentMerchantProductImage(Long userId, Long spuId,Long imageId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus, 1)
        );

        if (merchant == null) {
            throw new BusinessException(40301,"商家不存在或已停用");
        }

        // 商品必须属于当前商家
        ProductSpu spu = productSpuMapper.selectOne(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getId, spuId)
                        .eq(ProductSpu::getMerchantId, merchant.getId())
        );
        if (spu == null) {
            throw new BusinessException(40401, "商品不存在");
        }

        // 同时校验图片 ID 与 SPU ID ，避免删除其他商品的图片
        ProductImage image = productImageMapper.selectOne(
                new LambdaQueryWrapper<ProductImage>()
                        .eq(ProductImage::getId, imageId)
                        .eq(ProductImage::getSpuId, spuId)
        );
        if (image == null) {
            throw new BusinessException(40401, "商品图片不存在");
        }

        int affectedRows = productImageMapper.deleteById(image.getId());
        if (affectedRows != 1) {
            throw new BusinessException(50000, "删除商品图片失败");
        }

        String objectKey = image.getObjectKey();

        // 仅在数据库事务提交后清理 OSS，避免事务回滚导致图片丢失
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ossStorageService.deleteQuietly(objectKey);
                    }
                }
        );
    }
}
