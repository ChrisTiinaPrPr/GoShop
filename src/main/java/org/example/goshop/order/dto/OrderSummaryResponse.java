package org.example.goshop.order.dto;

import org.example.goshop.order.entity.MallOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 不包含地址、收货人和手机号的订单摘要。
 *
 * <p>该 DTO 可用于买家订单列表及 Agent 的订单查询门面。
 * 严禁向该 DTO 增加 addressSnapshotJson。</p>
 */
public record OrderSummaryResponse(
        String orderNo,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        List<OrderItemSummaryResponse> items,

        /**
         * 该订单实际包含多少条订单商品记录。
         */
        int itemLineCount,

        /**
         * true 表示 items 只返回了部分商品摘要。
         */
        boolean itemsTruncated
) {
    public OrderSummaryResponse {
        items = items == null
                ? List.of()
                : List.copyOf(items);
    }

    public static OrderSummaryResponse from(
            MallOrder order,
            List<OrderItemSummaryResponse> items,
            int itemLineCount,
            boolean itemsTruncated
    ) {
        return new OrderSummaryResponse(
                order.getOrderNo(),
                order.getStatus(),
                order.getTotalAmountCent(),
                order.getPayAmountCent(),
                order.getExpireAt(),
                order.getPaidAt(),
                order.getShippedAt(),
                order.getCreatedAt(),
                items,
                itemLineCount,
                itemsTruncated
        );
    }
}
