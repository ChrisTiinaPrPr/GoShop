package org.example.goshop.product.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.mapper.ProductDetailResponse;
import org.example.goshop.product.service.ProductService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "商品")
@Validated
@RestController
@RequestMapping("/api/v1/buyer")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "商品分页查询")
    @GetMapping("/products")
    public Result<PageResult<ProductListResponse>> listProducts(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为1")
            long page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页记录数最小为1")
            @Max(value = 100, message = "每页记录数最大为100")
            long pageSize,

            @RequestParam(required = false)
            @Positive(message = "分类ID必须为正数")
            Long categoryId,

            @RequestParam(required = false)
            @Size(max = 50, message = "关键字长度不能超过50个字符")
            String keyword,

            @RequestParam(defaultValue = "latest")
            @Pattern(
                    regexp = "latest|sales|priceAsc|priceDesc",
                    message = "sort仅支持latest、sales、priceAsc、priceDesc"
            )
            String sort
    ) {
        return Result.ok(
            productService.listPublicProducts(page, pageSize, categoryId, keyword, sort)
        );
    }

    @Operation(summary = "获取商品详情")
    @GetMapping("/products/{id}")
    public Result<ProductDetailResponse> getProductDetail (
            @PathVariable
            @Positive(message = "商品ID必须为正数")
            Long id
    ) {
        return Result.ok(
            productService.getPublicProductDetail(id)
        );
    }
}
