package org.example.goshop.payment.dto;

public record CreatePaymentResponse(
        String paymentNo,
        PaymentChannel channel,
        Long amountCent,
        // 仅 ALIPAY 渠道返回 HTML form；BALANCE 渠道固定为 null
        String alipayForm
) {
}
