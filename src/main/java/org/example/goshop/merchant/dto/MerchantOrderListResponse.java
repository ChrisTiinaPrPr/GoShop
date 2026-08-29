package org.example.goshop.merchant.dto;

import java.time.LocalDateTime;

public record MerchantOrderListResponse(
        String orderNo,
        String status,
        Long payAmountCent,
        String receiver,
        String receiverPhone,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime createdAt
) {
}
