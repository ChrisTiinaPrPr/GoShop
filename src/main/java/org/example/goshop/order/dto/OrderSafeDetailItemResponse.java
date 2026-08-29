package org.example.goshop.order.dto;

import org.example.goshop.order.entity.OrderItem;

/**
 * 不包含用户隐私的订单商品快照。
 *
 * <p>这里读取的是用户下单时保存的商品快照，
 * 不应再查询商品当前名称、价格或规格替换它。</p>
 */
public record OrderSafeDetailItemResponse(
        Long spuId,
        Long skuId,
        String productTitle,
        String productImage,
        String specsJson,
        Long unitPriceCent,
        Integer quantity,
        Long subtotalCent
) {
    /**
     * 将订单商品实体转换成隐私安全 DTO。
     *
     * <p>相比现有 OrderDetailItemResponse，这里补充了 quantity，
     * Agent 回答购买数量时不能通过金额自行推算。</p>
     */
    public static OrderSafeDetailItemResponse from(
            OrderItem item
    ) {
        return new OrderSafeDetailItemResponse(
                item.getSpuId(),
                item.getSkuId(),
                item.getProductTitle(),
                item.getProductImage(),
                item.getSpecsJson(),
                item.getUnitPriceCent(),
                item.getQuantity(),
                item.getSubtotalCent()
        );
    }
}
