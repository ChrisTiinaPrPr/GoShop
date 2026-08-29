package org.example.goshop.merchant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.CreateMerchantCategoryRequest;
import org.example.goshop.merchant.dto.MerchantCategoryResponse;
import org.example.goshop.merchant.dto.UpdateMerchantCategoryRequest;
import org.example.goshop.merchant.service.MerchantCategoryService;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "商家分类管理")
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
@Validated
public class MerchantCategoryController {

    private final MerchantCategoryService merchantCategoryService;

    @Operation(summary = "获取当前商家的店内分类树")
    @GetMapping("/categories")
    public Result<List<MerchantCategoryResponse>> listCurrentMerchantCategories(Authentication  authentication) {
        // JwtAuthenticationFilter 已将 userId 写入 principal
        Long userId = (Long) authentication.getPrincipal();

        return Result.ok(merchantCategoryService.listCurrentMerchantCategories(userId));
    }

    @Operation(summary = "创建当前商家的店内分类")
    @PostMapping("/categories")
    public Result<MerchantCategoryResponse> createCurrentMerchantCategory(
            Authentication  authentication,
            @Valid @RequestBody CreateMerchantCategoryRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();

        return Result.ok(merchantCategoryService.createCurrentMerchantCategory(userId, request));
    }

    @Operation(summary = "更新当前商家的店内分类")
    @PatchMapping("/categories/{id}")
    public Result<MerchantCategoryResponse> updateCurrentMerchantCategory(
            Authentication  authentication,
            @PathVariable @Positive(message = "id必须为正数") Long id,
            @Valid @RequestBody UpdateMerchantCategoryRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();

        return Result.ok(merchantCategoryService.updateCurrentMerchantCategory(userId, id, request));
    }

    @Operation(summary = "删除当前商家的店内分类")
    @DeleteMapping("/categories/{id}")
    public Result<Void> deleteCurrentMerchantCategory(
            Authentication  authentication,
            @PathVariable @Positive(message = "id必须为正数") Long id
    ) {
        Long userId = (Long) authentication.getPrincipal();
        merchantCategoryService.deleteCurrentMerchantCategory(userId, id);
        return Result.ok();
    }

}
