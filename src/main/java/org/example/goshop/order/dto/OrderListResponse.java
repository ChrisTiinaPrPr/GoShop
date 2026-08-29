package org.example.goshop.order.dto;

import org.example.goshop.order.entity.MallOrder;

import java.time.LocalDateTime;

/**
 * 订单列表摘要：不反悔地址快照和订单项，详情接口再查询完整数据。
 */
public record OrderListResponse(
        String orderNo,
        Long merchantId,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime createdAt
) {
    public static OrderListResponse from(MallOrder order) {
        return new OrderListResponse(
                order.getOrderNo(),
                order.getMerchantId(),
                order.getStatus(),
                order.getTotalAmountCent(),
                order.getPayAmountCent(),
                order.getExpireAt(),
                order.getCreatedAt()
        );
    }
}
