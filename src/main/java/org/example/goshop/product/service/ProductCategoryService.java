package org.example.goshop.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.product.dto.CategoryTreeResponse;
import org.example.goshop.product.entity.ProductCategory;
import org.example.goshop.product.mapper.ProductCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    private final ProductCategoryMapper productCategoryMapper;

    public List<CategoryTreeResponse> listPlatformCategoryTree() {
        // 仅返回启用的平台分类，商家私有分类不能显示
        List<ProductCategory> categories = productCategoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .isNull(ProductCategory::getMerchantId)
                        .eq(ProductCategory::getStatus, 1)
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId)

        );

        Map<Long, CategoryTreeResponse> nodeMap = new HashMap<>();
        for (ProductCategory category : categories) {
            nodeMap.put(category.getId(), CategoryTreeResponse.from(category));
        }
        List<CategoryTreeResponse> roots = new ArrayList<>();

        // 第二次遍历组装树，避免每个节点重复查询数据库
        for (ProductCategory category : categories) {
            CategoryTreeResponse current = nodeMap.get(category.getId());
            Long parentId = category.getParentId();

            if (parentId == null || parentId == 0) {
                roots.add(current);
                continue;
            }

            CategoryTreeResponse parent = nodeMap.get(parentId);
            if (parent == null) {
                // 父分类不存在或已经被禁用时，不丢失该分类，提升为根节点展示
                roots.add(current);
            } else {
                parent.getChildren().add(current);
            }
        }
        return roots;
    }
}
