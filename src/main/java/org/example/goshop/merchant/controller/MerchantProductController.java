package org.example.goshop.merchant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.*;
import org.example.goshop.merchant.service.MerchantProductService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "商家商品管理")
@Validated
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantProductController {

    private final MerchantProductService merchantProductService;

    @Operation(summary = "查询当前商家商品详情")
    @GetMapping("/products/{id}")
    public Result<MerchantProductDetailResponse> getCurrentMerchantProduct(
            Authentication authentication,
            @PathVariable @Positive(message = "id 必须大于 0") Long id
    ) {
        return Result.ok(merchantProductService.getCurrentMerchantProductDetail(
                (Long) authentication.getPrincipal(), id));
    }

    @Operation(summary = "查询当前商家商品列表")
    @GetMapping("/products")
    public Result<PageResult<MerchantProductListResponse>> listCurrentMerchantProducts(
            Authentication authentication,

            @RequestParam(defaultValue = "1")
            @Min(value = 1,message = "page必须大于等于1")
            long page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1,message = "pageSize必须大于等于1")
            @Max(value = 100,message = "pageSize必须小于等于100")
            long pageSize,

            @RequestParam(required = false)
            @Positive(message = "categoryId必须大于0")
            Long categoryId,

            @RequestParam(required = false)
            @Size(max = 50,message = "keyword长度不能超过50")
            String keyword,

            @RequestParam(required = false)
            @Min(value = 0,message = "status只能为0或1")
            @Max(value = 1,message = "status只能为0或1")
            Integer status,

            @RequestParam(defaultValue = "latest")
            @Pattern(regexp = "latest|sales|priceAsc|priceDesc",message = "sort只能为latest,sales,priceAsc，priceDesc")
            String sort
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(
                merchantProductService.listCurrentMerchantProducts(
                        userId,
                        page,
                        pageSize,
                        categoryId,
                        keyword,
                        status,
                        sort
                )
        );
    }

    @Operation(summary = "创建SPU与SKU")
    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<CreateMerchantProductResponse> createCurrentMerchantProduct(
            Authentication authentication,

            // multipart 的文本字段，避免 RequestPart 对复杂 record 的转换异常
            @RequestParam("product") String productJson,

            @RequestPart("mainImage") MultipartFile mainImage
    ) {
        Long userId = (Long) authentication.getPrincipal();

        CreateMerchantProductRequest request =
                merchantProductService.parseCreateProductRequest(productJson);

        return Result.ok(
                merchantProductService.createCurrentMerchantProduct(
                        userId,
                        request,
                        mainImage
                )
        );
    }

    @Operation(summary = "编辑当前商家的商品与 SKU")
    @PatchMapping(value = "/products/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MerchantProductDetailResponse> updateCurrentMerchantProduct(
            Authentication authentication,

            @PathVariable
            @Positive(message = "id 必须大于 0")
            Long id,

            // 不再用 RequestPart 直接转换复杂 DTO
            @RequestParam("product") String productJson,

            @RequestPart(value = "mainImage", required = false)
            MultipartFile mainImage
    ) {
        Long userId = (Long) authentication.getPrincipal();

        UpdateMerchantProductRequest request =
                merchantProductService.parseUpdateProductRequest(productJson);

        return Result.ok(
                merchantProductService.updateCurrentMerchantProduct(
                        userId, id, request, mainImage
                )
        );
    }

    @Operation(summary = "修改当前商家的上下架状态")
    @PatchMapping("/products/{id}/status")
    public Result<MerchantProductStatusResponse> updateCurrentMerchantProductStatus(
            Authentication authentication,
            @PathVariable
            @Positive(message = "id 必须大于 0")
            Long id,

            @Valid
            @RequestBody
            UpdateMerchantProductStatusRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(
                merchantProductService.updateCurrentMerchantProductStatus(
                        userId, id, request
                )
        );
    }


}
