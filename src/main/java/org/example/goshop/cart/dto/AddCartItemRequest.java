package org.example.goshop.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(

        @NotNull(message = "SKU ID 不能为空")
        @Positive(message = "SKU ID 必须为正数")
        Long skuId,

        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须为正数")
        Integer quantity
) {
}
