package org.example.goshop.merchant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.service.MerchantService;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.service.ProductService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商家")
@Validated
@RestController
@RequestMapping("/api/v1/buyer")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final ProductService productService;

    @Operation(summary = "获取商家信息")
    @GetMapping("/merchants/{id}")
    public Result<MerchantProfileResponse> getMerchantProfile(
            @PathVariable
            @Positive(message = "商家ID必须为正数")
            Long id
    ) {
        return Result.ok(merchantService.getMerchantProfile(id));
    }

    /**
     * 分页查询指定启用店铺的公开商品。
     *
     * <p>该接口允许游客访问，但公开不等于放宽商品可见性：Service 会
     * 先确认商家处于启用状态，再在 SQL 中同时限制 merchant_id、SPU
     * 上架状态和 SKU 启用状态。</p>
     */
    @Operation(summary = "分页查询店铺公开商品")
    @GetMapping("/merchants/{merchantId}/products")
    public Result<PageResult<ProductListResponse>>
    listMerchantProducts(
            @PathVariable
            @Positive(message = "商家ID必须为正数")
            Long merchantId,

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
            @Size(
                    max = 50,
                    message = "关键字长度不能超过50个字符"
            )
            String keyword,

            @RequestParam(defaultValue = "latest")
            @Pattern(
                    regexp = "latest|sales|priceAsc|priceDesc",
                    message = "sort仅支持latest、sales、priceAsc、priceDesc"
            )
            String sort
    ) {
        return Result.ok(
                productService.listPublicMerchantProducts(
                        merchantId,
                        page,
                        pageSize,
                        categoryId,
                        keyword,
                        sort
                )
        );
    }

}
