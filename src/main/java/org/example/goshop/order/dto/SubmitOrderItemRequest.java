package org.example.goshop.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 商品列表中的每一项，包含 skuId 和购买数量 quantity
 * @param skuId
 * @param quantity
 */
public record SubmitOrderItemRequest(
        @NotNull(message = " skuId 不能为空")
        @Positive(message = " skuId 必须大于0")
        Long skuId,

        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须大于0")
        Integer quantity
) {
}
