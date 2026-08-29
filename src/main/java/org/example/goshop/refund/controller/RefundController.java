package org.example.goshop.refund.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.refund.dto.CreateRefundRequest;
import org.example.goshop.refund.dto.CreateRefundResponse;
import org.example.goshop.refund.service.RefundService;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "退款")
@Validated
@RestController
@RequestMapping("/api/v1/buyer/orders")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @Operation(summary = "用户申请整单退款")
    @PostMapping("/{orderNo}/refunds")
    public Result<CreateRefundResponse> applyRefund(
            Authentication authentication,

            @PathVariable
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号长度不能超过 64 个字符")
            String orderNo,

            @Valid
            @RequestBody
            CreateRefundRequest request
    ) {
        // 用户身份只能从 JWT 中获取，不能由请求体传入
        Long userId = (Long) authentication.getPrincipal();

        return Result.ok(
                refundService.applyRefund(
                        userId,
                        orderNo,
                        request
                )
        );
    }
}
