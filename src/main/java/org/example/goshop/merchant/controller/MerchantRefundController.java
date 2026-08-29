package org.example.goshop.merchant.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.MerchantRefundResponse;
import org.example.goshop.merchant.dto.ReviewRefundRequest;
import org.example.goshop.merchant.service.MerchantRefundService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/merchant/refunds")
@RequiredArgsConstructor
public class MerchantRefundController {
    private final MerchantRefundService merchantRefundService;

    @GetMapping
    public Result<PageResult<MerchantRefundResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) String status) {
        return Result.ok(merchantRefundService.list((Long) authentication.getPrincipal(), page, pageSize, status));
    }

    @GetMapping("/{refundNo}")
    public Result<MerchantRefundResponse> detail(Authentication authentication,
                                                  @PathVariable @NotBlank String refundNo) {
        return Result.ok(merchantRefundService.detail((Long) authentication.getPrincipal(), refundNo));
    }

    @PostMapping("/{refundNo}/approve")
    public Result<MerchantRefundResponse> approve(Authentication authentication,
            @PathVariable @NotBlank String refundNo, @Valid @RequestBody ReviewRefundRequest request) {
        return Result.ok(merchantRefundService.approve((Long) authentication.getPrincipal(), refundNo, request));
    }

    @PostMapping("/{refundNo}/reject")
    public Result<MerchantRefundResponse> reject(Authentication authentication,
            @PathVariable @NotBlank String refundNo, @Valid @RequestBody ReviewRefundRequest request) {
        return Result.ok(merchantRefundService.reject((Long) authentication.getPrincipal(), refundNo, request));
    }
}
