package org.example.goshop.merchant.dto;

import org.example.goshop.order.dto.OrderAddressSnapshotResponse;
import org.example.goshop.order.dto.OrderDetailItemResponse;

import java.time.LocalDateTime;
import java.util.List;

public record MerchantOrderDetailResponse(
        String orderNo,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        String paymentChannel,
        LocalDateTime paidAt,
        String shippingCompany,
        String trackingNo,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        OrderAddressSnapshotResponse address,
        List<OrderDetailItemResponse> items
) {
}
