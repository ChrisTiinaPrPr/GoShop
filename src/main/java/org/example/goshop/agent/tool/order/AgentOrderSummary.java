package org.example.goshop.agent.tool.order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个不包含地址、收货人和手机号的订单摘要。
 */
public record AgentOrderSummary(
        String orderNo,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        List<AgentOrderItemSummary> items,
        int itemLineCount,
        boolean itemsTruncated
) {
    public AgentOrderSummary {
        items = items == null
                ? List.of()
                : List.copyOf(items);

        if (orderNo == null
                || orderNo.isBlank()
                || orderNo.length() > 64) {
            throw new IllegalArgumentException(
                    "订单号不合法"
            );
        }

        if (totalAmountCent == null
                || totalAmountCent < 0
                || payAmountCent == null
                || payAmountCent < 0) {
            throw new IllegalArgumentException(
                    "订单金额不能为负数"
            );
        }

        if (itemLineCount < 0) {
            throw new IllegalArgumentException(
                    "订单商品行数不能为负数"
            );
        }
    }
}
