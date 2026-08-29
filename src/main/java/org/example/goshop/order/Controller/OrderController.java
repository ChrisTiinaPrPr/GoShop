package org.example.goshop.order.Controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.order.dto.OrderDetailResponse;
import org.example.goshop.order.dto.OrderListResponse;
import org.example.goshop.order.dto.SubmitOrderRequest;
import org.example.goshop.order.dto.SubmitOrderResponse;
import org.example.goshop.order.service.OrderService;
import org.example.goshop.order.service.OrderCancellationService;
import org.example.goshop.payment.dto.CreatePaymentRequest;
import org.example.goshop.payment.dto.CreatePaymentResponse;
import org.example.goshop.payment.service.PaymentService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "订单")
@RestController
@RequestMapping("/api/v1/buyer/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;
    private final OrderCancellationService orderCancellationService;
    private final PaymentService paymentService;

    @Operation(summary = "提交订单")
    @PostMapping
    public Result<SubmitOrderResponse> submitOrder(
            Authentication authentication,

            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key 不能为空")
            @Size(max = 64, message = "Idempotency-Key 长度不能超过 64 个字符")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Idempotency-Key 只能包含字母、数字、下划线和短横线")
            String idempotencyKey,

            @Valid
            @RequestBody
            SubmitOrderRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(orderService.submitOrder(userId, idempotencyKey, request));
    }

    @Operation(summary = "获取当前用户订单分页列表")
    @GetMapping
    public Result<PageResult<OrderListResponse>> listOrders(
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
        return Result.ok(orderService.listUserOrders(userId, page, pageSize));
    }

    @Operation(summary = "获取当前用户订单详情")
    @GetMapping("/{orderNo}")
    public Result<OrderDetailResponse> geiOrderDetail(
            Authentication authentication,

            @PathVariable
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号长度不能超过 64 个字符")
            String orderNo
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(orderService.getUserOrderDetail(userId, orderNo));
    }

    @PostMapping("/{orderNo}/payment")
    @Operation(summary = "创建支付单")
    public Result<CreatePaymentResponse> createPayment(
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 64,message = "订单号长度不能超过 64 个字符") String orderNo,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        // Service 内部校验订单归属，待支付状态、过期时间和支付金额
        return Result.ok(paymentService.createPayment(userId, orderNo, request));
    }

    @PostMapping("/{orderNo}/receipt")
    @Operation(summary = "确认收货")
    public Result<Void> confirmReceipt(
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 64) String orderNo
    ) {
        Long userId = (Long) authentication.getPrincipal();
        orderService.confirmReceipt(userId, orderNo);
        return Result.ok();
    }

    /**
     * 买家主动取消自己的待支付订单。
     *
     * <p>用户身份只从 JWT Authentication 中读取。Service 使用订单号与
     * userId 联合加锁查询，并与支付回调、余额支付和超时取消竞争同一订单行锁。</p>
     */
    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消待支付订单")
    public Result<Void> cancelOrder(
            Authentication authentication,
            @PathVariable
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号长度不能超过 64 个字符")
            String orderNo
    ) {
        Long userId = (Long) authentication.getPrincipal();
        orderCancellationService.cancelByBuyer(userId, orderNo);
        return Result.ok();
    }
}
