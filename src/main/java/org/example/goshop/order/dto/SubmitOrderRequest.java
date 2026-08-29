package org.example.goshop.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 整次结算的公共信息，只包含收货地址 addressId 和商品列表 items
 * @param addressId
 * @param items
 */
public record SubmitOrderRequest(
        @NotNull(message = "addressId 不能为空")
        @Positive(message = "addressId 必须大于 0")
        Long addressId,

        @NotEmpty(message = "至少选择一个商品")
        @Size(max = 50, message = "单次最多提交 50 个 SKU")
        List<@Valid SubmitOrderItemRequest> items
) {
}
