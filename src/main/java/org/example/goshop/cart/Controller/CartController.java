package org.example.goshop.cart.Controller;

import com.aliyun.core.annotation.Path;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.cart.dto.AddCartItemRequest;
import org.example.goshop.cart.dto.CartItemResponse;
import org.example.goshop.cart.dto.UpdateCartItemRequest;
import org.example.goshop.cart.service.CartService;
import org.example.goshop.common.api.Result;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@Tag(name = "购物车")
@RestController
@RequestMapping("/api/v1/buyer/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "查询当前用户购物车")
    @GetMapping("/items")
    public Result<List<CartItemResponse>> listCurrentUserCartItems(
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(cartService.listCurrentUserCartItems(userId));
    }

    @Operation(summary = "加入购物车")
    @PostMapping("/items")
    public Result<CartItemResponse> addCurrentUserCartItem(
            Authentication authentication,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(cartService.addCurrentUserCartItem(userId,request));
    }

    @Operation(summary = "修改购物车商品数量或勾选状态")
    @PatchMapping("/items/{skuId}")
    public Result<CartItemResponse> updateCurrentUserCartItem(
        Authentication authentication,

        @PathVariable
        @Positive(message = "SKU ID必须为正数")
        Long skuId,

        @Valid
        @RequestBody
        UpdateCartItemRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();

        return Result.ok(cartService.updateCurrentUserCartItem(userId,skuId,request));
    }

    @Operation(summary = "删除购物车商品")
    @DeleteMapping("/items/{skuId}")
    public Result<Void> deleteCurrentUserCartItem(
            Authentication authentication,
            @PathVariable
            @Positive(message = "SKU ID必须为正数")
            Long skuId
    ) {
        Long userId = (Long) authentication.getPrincipal();

        cartService.deleteCurrentUserCartItem(userId,skuId);
        return Result.ok();
    }

    @Operation(summary = "清空失效购物车项")
    @DeleteMapping("/items")
    public Result<Void> clearInvalidCurrentUserCartItems(
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        cartService.clearInvalidCurrentUserCartItems(userId);
        return Result.ok();
    }
}
