package org.example.goshop.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.review.dto.CreateProductReviewRequest;
import org.example.goshop.review.dto.OrderItemReviewResponse;
import org.example.goshop.review.dto.ProductReviewPageResponse;
import org.example.goshop.review.dto.ProductReviewResponse;
import org.example.goshop.review.service.ProductReviewService;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "商品评价")
@Validated
@RestController
@RequestMapping("/api/v1/buyer")
@RequiredArgsConstructor
public class ProductReviewController {

    private final ProductReviewService reviewService;

    @Operation(summary = "评价本人已完成订单中的商品")
    @PostMapping("/reviews")
    public Result<ProductReviewResponse> createReview(
            Authentication authentication,
            @Valid @RequestBody CreateProductReviewRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(reviewService.createReview(userId, request));
    }

    @Operation(summary = "查询本人订单中各商品的评价状态")
    @GetMapping("/reviews/orders/{orderNo}")
    public Result<List<OrderItemReviewResponse>> listOrderReviews(
            Authentication authentication,
            @PathVariable
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号长度不能超过 64 个字符")
            String orderNo
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(reviewService.listOrderReviews(userId, orderNo));
    }

    @Operation(summary = "分页查询商品公开评价")
    @GetMapping("/products/{productId}/reviews")
    public Result<ProductReviewPageResponse> listPublicProductReviews(
            @PathVariable
            @Positive(message = "商品 ID 必须为正数")
            Long productId,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为 1")
            long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页记录数最小为 1")
            @Max(value = 50, message = "每页记录数最大为 50")
            long pageSize
    ) {
        return Result.ok(
                reviewService.listPublicProductReviews(productId, page, pageSize)
        );
    }
}
