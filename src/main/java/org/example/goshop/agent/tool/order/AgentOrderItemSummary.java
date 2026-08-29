package org.example.goshop.agent.tool.order;

/**
 * 允许发送给模型的订单商品快照摘要。
 */
public record AgentOrderItemSummary(
        Long productId,
        Long skuId,
        String productTitle,
        String productImage,
        Long unitPriceCent,
        Integer quantity,
        Long subtotalCent
) {
    public AgentOrderItemSummary {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException(
                    "订单商品数量必须为正数"
            );
        }

        if (unitPriceCent == null
                || unitPriceCent < 0
                || subtotalCent == null
                || subtotalCent < 0) {
            throw new IllegalArgumentException(
                    "订单商品金额不能为负数"
            );
        }
    }
}
