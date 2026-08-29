package org.example.goshop.agent.tool.order;

import java.util.List;

/**
 * list_orders 返回给模型的结构化结果。
 */
public record AgentOrderListResult(
        List<AgentOrderSummary> orders,
        long total,
        boolean hasMore,
        AgentOrderStatusFilter statusFilter
) {
    public AgentOrderListResult {
        orders = orders == null
                ? List.of()
                : List.copyOf(orders);

        if (total < 0) {
            throw new IllegalArgumentException(
                    "订单总数不能为负数"
            );
        }

        statusFilter =
                statusFilter == null
                        ? AgentOrderStatusFilter.ALL
                        : statusFilter;
    }
}
