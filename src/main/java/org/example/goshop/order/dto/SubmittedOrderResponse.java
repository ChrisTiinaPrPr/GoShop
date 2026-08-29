package org.example.goshop.order.dto;

import java.time.LocalDateTime;

public record SubmittedOrderResponse(
        String orderNo,
        Long merchantId,
        Long payAmountCent,
        String status,
        LocalDateTime expireAt
) {
}
