package org.example.goshop.agent.tool.cart;

import java.util.List;

/**
 * get_cart 返回给模型的结构化购物车数据。
 */
public record AgentCartResult(
        List<AgentCartItem> items,

        /**
         * 购物车中实际存在的 SKU 项数，包括本次因数量限制未返回的项。
         */
        int totalItemCount,

        /**
         * 所有购物车项的商品数量总和。
         */
        long totalQuantity,

        /**
         * 有效且已勾选商品的总金额，单位为分。
         */
        long selectedTotalCent,

        /**
         * 有效且已勾选的 SKU 项数。
         */
        int selectedValidItemCount,

        /**
         * true 表示购物车项超过 Agent 最大返回数量。
         */
        boolean truncated
) {
    public AgentCartResult {
        items = items == null
                ? List.of()
                : List.copyOf(items);

        if (totalItemCount < 0
                || totalQuantity < 0
                || selectedTotalCent < 0
                || selectedValidItemCount < 0) {
            throw new IllegalArgumentException(
                    "购物车统计数据不能为负数"
            );
        }
    }
}
