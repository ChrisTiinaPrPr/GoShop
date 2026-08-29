package org.example.goshop.order.dto;

import java.util.List;

public record SubmitOrderResponse(
        List<SubmittedOrderResponse> orders,
        Long totalPaymentAmountCent
) {
}
