package org.example.goshop.cart.dto;

import jakarta.validation.constraints.Positive;

public record UpdateCartItemRequest(
        // 不传表示不修改数量
        @Positive(message = "购买数量必须大于0")
        Integer quantity,

        // 不传表示不修改勾选状态
        Boolean selected
) {
}
