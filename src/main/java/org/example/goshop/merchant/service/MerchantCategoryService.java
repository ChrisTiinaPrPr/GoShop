package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.CreateMerchantCategoryRequest;
import org.example.goshop.merchant.dto.MerchantCategoryResponse;
import org.example.goshop.merchant.dto.UpdateMerchantCategoryRequest;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.product.entity.ProductCategory;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductCategoryMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PrivateKey;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MerchantCategoryService {

    private final MerchantMapper merchantMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final ProductSpuMapper productSpuMapper;

    public List<MerchantCategoryResponse> listCurrentMerchantCategories(Long userId) {
        // userId 来自JWT
        Merchant merchant = getCurrentEnabledMerchant(userId);

        // 商家后台需要看到全部店内分类，包括已禁用分类
        List<ProductCategory> categories = productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getMerchantId, merchant.getId())
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId)
        );

        Map<Long, MerchantCategoryResponse> nodeMap = new HashMap<>();
        for (ProductCategory category : categories) {
            nodeMap.put(category.getId(), MerchantCategoryResponse.from(category));
        }
        List<MerchantCategoryResponse> roots = new ArrayList<>();

        // 在内存中组装分类树，避免递归查询数据库
        for (ProductCategory category : categories) {
            MerchantCategoryResponse current = nodeMap.get(category.getId());
            Long parentId = category.getParentId();

            if (parentId == null || parentId == 0) {
                roots.add(current);
                continue;
            }

            MerchantCategoryResponse parent = nodeMap.get(parentId);
            if (parent == null) {
                // 父分类不存在或不属于当前商家时，仍让该分类可被商家管理
                roots.add(current);
            } else {
                parent.getChildren().add(current);
            }
        }
        return roots;
    }

    private Merchant getCurrentEnabledMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus,1)
        );
        if (merchant == null) {
            throw new BusinessException(40301, "商家不存在或已停用");
        }
        return merchant;
    }

    @Transactional
    public MerchantCategoryResponse createCurrentMerchantCategory(
            Long userId,
            CreateMerchantCategoryRequest request
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);

        // 若创建二级或更深层分类，父分类必须属于当前商家
        if (request.parentId() != null) {
            ProductCategory parent = productCategoryMapper.selectOne(
                    new LambdaQueryWrapper<ProductCategory>()
                            .eq(ProductCategory::getId, request.parentId())
                            .eq(ProductCategory::getMerchantId, merchant.getId())
            );

            if (parent == null) {
                // 不区分父分类不存在与不属于当前商家，防止越权探测分类 ID
                throw new BusinessException(40401, "父分类不存在");
            }
        }
        ProductCategory category = new ProductCategory();
        category.setMerchantId(merchant.getId());
        category.setParentId(request.parentId());
        category.setName(request.name().trim());

        // 新建分类默认应用；后续通过修改接口处理启停
        category.setStatus(1);
        category.setSort(request.sort() == null ? 0 : request.sort());
        productCategoryMapper.insert(category);
        return MerchantCategoryResponse.from(category);
    }

    @Transactional
    public MerchantCategoryResponse updateCurrentMerchantCategory(
            Long userId,
            Long categoryId,
            UpdateMerchantCategoryRequest request
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);

        ProductCategory category = productCategoryMapper.selectOne(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getId, categoryId)
                        .eq(ProductCategory::getMerchantId, merchant.getId())
        );

        // 不区分分类不存在与不属于当前商家，防止越权探测分类 ID
        if (category == null) {
            throw new BusinessException(40401, "分类不存在");
        }

        // PATCH 请求至少要提供一个可更新字段
        if (request.name() == null && request.parentId() == null && request.sort() == null && request.status() == null) {
            throw new BusinessException(40001, "至少要提供一个可更新字段");
        }

        if (request.name() != null) {
            category.setName(request.name().trim());
        }

        if (request.sort() != null) {
            category.setSort(request.sort());
        }

        if (request.status() != null) {
            category.setStatus(request.status());
        }

        if (request.parentId() != null) {
            updateCategoryParent(category, request.parentId(),merchant.getId());
        }

        productCategoryMapper.updateById(category);
        return MerchantCategoryResponse.from(category);
    }

    private void updateCategoryParent(ProductCategory category, Long newParentId, Long merchantId) {

        // PATCH 中使用 0 表示移动为一级分类，数据库统一存储为 null
        if (newParentId == 0) {
            category.setParentId(null);
            return;
        }

        if (category.getId().equals(newParentId)) {
            throw new BusinessException(42201, "不能将分类移动为自己的子分类");
        }

        // 一次读取当前商家的所有分类，用于归属检验与循环检测
        List<ProductCategory> categories = productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getMerchantId, merchantId)
        );

        Map<Long, ProductCategory> categoryMap = new HashMap<>();
        for (ProductCategory item : categories) {
            categoryMap.put(item.getId(), item);
        }

        if (!categoryMap.containsKey(newParentId)) {
            throw new BusinessException(40401, "父分类不存在");
        }

        // 沿新父分类向上追溯；若遇上当前分类，说明会形成循环
        Set<Long> visited = new HashSet<>();
        Long currentId = newParentId;

        while (currentId != null && currentId != 0) {
            if (!visited.add(currentId)) {
                throw new BusinessException(42201, "分类层级存在循环");
            }

            if (category.getId().equals(currentId)) {
                throw new BusinessException(42201, "不能移动到自己的子分类下");
            }

            ProductCategory current = categoryMap.get(currentId);
            if (current == null) {
                // 历史脏数据的父节点不属于当前商家时，中断溯源即可
                break;
            }

            currentId = current.getParentId();
        }

        category.setParentId(newParentId);

    }

    @Transactional
    public void deleteCurrentMerchantCategory(Long userId, Long categoryId) {
        Merchant merchant = getCurrentEnabledMerchant(userId);

        // 同时按分类 ID 与商家 ID 查询，防止越权删除
        ProductCategory category = productCategoryMapper.selectOne(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getId, categoryId)
                        .eq(ProductCategory::getMerchantId, merchant.getId())
        );

        if (category == null) {
            throw new BusinessException(40401, "分类不存在");
        }

        // 有子分类时禁止删除，避免子分类成为无父节点的脏数据
        long childCount = productCategoryMapper.selectCount(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getParentId, categoryId)
        );

        if (childCount > 0) {
            throw new BusinessException(40901, "该分类下存在子分类，请先删除或迁移子分类");
        }

        // 无论是否上架商品，只要仍关联该分类，就不允许删除
        long productCount = productSpuMapper.selectCount(
                new LambdaQueryWrapper<ProductSpu>()
                        .eq(ProductSpu::getMerchantId, merchant.getId())
                        .eq(ProductSpu::getCategoryId, categoryId)
        );
        if (productCount > 0) {
            throw new BusinessException(40901, "该分类下存在商品，请先删除或转移商品");
        }

        productCategoryMapper.deleteById(category.getId());
    }
}
