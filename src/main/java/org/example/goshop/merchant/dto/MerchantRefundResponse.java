package org.example.goshop.merchant.dto;

import java.time.LocalDateTime;

public record MerchantRefundResponse(
        String refundNo,
        String orderNo,
        String orderStatus,
        String refundStatus,
        String paymentChannel,
        Long amountCent,
        String reason,
        String reviewRemark,
        LocalDateTime appliedAt,
        LocalDateTime processedAt
) {
}
