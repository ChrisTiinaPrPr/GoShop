package org.example.goshop.order.dto;

import org.example.goshop.order.entity.OrderItem;

/**
 * 订单列表使用的商品快照摘要。
 *
 * <p>使用下单时保存的商品快照，不读取商品当前价格，
 * 避免商品修改后影响历史订单事实。</p>
 */
public record OrderItemSummaryResponse(
        Long productId,
        Long skuId,
        String productTitle,
        String productImage,
        Long unitPriceCent,
        Integer quantity,
        Long subtotalCent
) {
    public static OrderItemSummaryResponse from(
            OrderItem item
    ) {
        return new OrderItemSummaryResponse(
                item.getSpuId(),
                item.getSkuId(),
                item.getProductTitle(),
                item.getProductImage(),
                item.getUnitPriceCent(),
                item.getQuantity(),
                item.getSubtotalCent()
        );
    }
}
