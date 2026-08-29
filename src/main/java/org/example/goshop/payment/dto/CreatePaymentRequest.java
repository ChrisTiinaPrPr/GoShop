package org.example.goshop.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(
        @NotNull(message = "支付渠道不能为空")
        PaymentChannel channel

) {
}
