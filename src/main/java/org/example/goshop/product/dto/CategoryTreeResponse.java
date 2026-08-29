package org.example.goshop.product.dto;

import lombok.Data;
import org.example.goshop.product.entity.ProductCategory;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryTreeResponse {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;

    // 下级分类；叶子节点返回空数组，而不是 null
    private List<CategoryTreeResponse> children = new ArrayList<>();

    public static CategoryTreeResponse from(ProductCategory category) {
        CategoryTreeResponse response = new CategoryTreeResponse();
        response.setId(category.getId());
        response.setParentId(category.getParentId());
        response.setName(category.getName());
        response.setSort(category.getSort());
        return response;
    }
}