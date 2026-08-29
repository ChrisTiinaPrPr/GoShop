package org.example.goshop.order.dto;

import org.example.goshop.order.entity.OrderItem;

/**
 * 订单项使用下单时的商品快照，不再查询商品当前价格或名称,对应订单中的某一件商品
 */
public record OrderDetailItemResponse(
        Long spuId,
        Long skuId,
        String productTitle,
        String productImage,
        String specsJson,
        Long unitPriceCent,
        Long subtotalCent
) {
    public static OrderDetailItemResponse from(OrderItem item) {
        return new OrderDetailItemResponse(
                item.getSpuId(),
                item.getSkuId(),
                item.getProductTitle(),
                item.getProductImage(),
                item.getSpecsJson(),
                item.getUnitPriceCent(),
                item.getSubtotalCent()
        );
    }
}
