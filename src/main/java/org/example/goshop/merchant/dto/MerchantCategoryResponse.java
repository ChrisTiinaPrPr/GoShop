package org.example.goshop.merchant.dto;

import lombok.Data;
import org.example.goshop.product.entity.ProductCategory;

import java.util.ArrayList;
import java.util.List;

@Data
public class MerchantCategoryResponse {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;

    // 0:禁用 1:启用
    private Integer status;

    private List<MerchantCategoryResponse> children = new ArrayList<>();

    public static MerchantCategoryResponse from(ProductCategory  category) {
        MerchantCategoryResponse response = new MerchantCategoryResponse();
        response.setId(category.getId());
        response.setParentId(category.getParentId());
        response.setName(category.getName());
        response.setSort(category.getSort());
        response.setStatus(category.getStatus());
        return response;
    }
}
