package org.example.goshop.favorite.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.favorite.dto.FavoriteProductResponse;
import org.example.goshop.favorite.dto.FavoriteStatusResponse;
import org.example.goshop.favorite.service.FavoriteService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商品收藏")
@Validated
@RestController
@RequestMapping("/api/v1/buyer/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "分页查询当前买家的商品收藏")
    @GetMapping
    public Result<PageResult<FavoriteProductResponse>> listFavorites(
            Authentication authentication,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为 1")
            long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页记录数最小为 1")
            @Max(value = 100, message = "每页记录数最大为 100")
            long pageSize
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(favoriteService.listFavorites(userId, page, pageSize));
    }

    @Operation(summary = "查询当前买家是否已收藏指定商品")
    @GetMapping("/{productId}/status")
    public Result<FavoriteStatusResponse> getFavoriteStatus(
            Authentication authentication,
            @PathVariable
            @Positive(message = "商品 ID 必须为正数")
            Long productId
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(favoriteService.getFavoriteStatus(userId, productId));
    }

    @Operation(summary = "收藏商品")
    @PostMapping("/{productId}")
    public Result<Void> addFavorite(
            Authentication authentication,
            @PathVariable
            @Positive(message = "商品 ID 必须为正数")
            Long productId
    ) {
        Long userId = (Long) authentication.getPrincipal();
        favoriteService.addFavorite(userId, productId);
        return Result.ok();
    }

    @Operation(summary = "取消收藏商品")
    @DeleteMapping("/{productId}")
    public Result<Void> removeFavorite(
            Authentication authentication,
            @PathVariable
            @Positive(message = "商品 ID 必须为正数")
            Long productId
    ) {
        Long userId = (Long) authentication.getPrincipal();
        favoriteService.removeFavorite(userId, productId);
        return Result.ok();
    }

}
