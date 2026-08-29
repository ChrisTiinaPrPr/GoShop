package org.example.goshop.product.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.product.dto.CategoryTreeResponse;
import org.example.goshop.product.entity.ProductCategory;
import org.example.goshop.product.service.ProductCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "商品分类")
@RestController
@RequestMapping("/api/v1/buyer")
@RequiredArgsConstructor
public class ProductCategoryController {

    private final ProductCategoryService productCategoryService;

    @Operation(summary = "获取平台分类树")
    @GetMapping("/categories")
    public Result<List<CategoryTreeResponse>> listCategories() {
        return Result.ok(productCategoryService.listPlatformCategoryTree());
    }
}
