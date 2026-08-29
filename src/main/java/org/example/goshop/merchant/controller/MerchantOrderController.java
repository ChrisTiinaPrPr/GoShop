package org.example.goshop.merchant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.*;
import org.example.goshop.merchant.service.MerchantOrderService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "商家订单管理")
@Validated
@RestController
@RequestMapping("/api/v1/merchant/orders")
@RequiredArgsConstructor
public class MerchantOrderController {
    private final MerchantOrderService merchantOrderService;

    @GetMapping
    @Operation(summary = "分页查询当前商家订单")
    public Result<PageResult<MerchantOrderListResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @Size(max = 64) String orderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt
    ) {
        return Result.ok(merchantOrderService.listOrders((Long) authentication.getPrincipal(),
                page, pageSize, status, orderNo, startAt, endAt));
    }

    @GetMapping("/{orderNo}")
    public Result<MerchantOrderDetailResponse> detail(
            Authentication authentication, @PathVariable @NotBlank String orderNo) {
        return Result.ok(merchantOrderService.getOrder((Long) authentication.getPrincipal(), orderNo));
    }

    @PostMapping("/{orderNo}/ship")
    public Result<MerchantOrderDetailResponse> ship(
            Authentication authentication, @PathVariable @NotBlank String orderNo,
            @Valid @RequestBody ShipOrderRequest request) {
        return Result.ok(merchantOrderService.ship((Long) authentication.getPrincipal(), orderNo, request));
    }
}
