package org.example.goshop.order.dto;

import org.example.goshop.order.entity.MallOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户订单详情：订单主体、地址快照、订单项快照。对应一家店铺的订单。可能包含多个 OrderDetailItemResponse
 */
public record OrderDetailResponse(
        String orderNo,
        Long merchantId,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        String shippingCompany,
        String trackingNo,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        OrderAddressSnapshotResponse address,
        List<OrderDetailItemResponse> items
) {
    public static OrderDetailResponse from(
            MallOrder order,
            OrderAddressSnapshotResponse address,
            List<OrderDetailItemResponse> items
    ) {
        return new OrderDetailResponse(
                order.getOrderNo(),
                order.getMerchantId(),
                order.getStatus(),
                order.getTotalAmountCent(),
                order.getPayAmountCent(),
                order.getExpireAt(),
                order.getPaidAt(),
                order.getShippingCompany(),
                order.getTrackingNo(),
                order.getShippedAt(),
                order.getCreatedAt(),
                address,
                items
        );
    }
}
