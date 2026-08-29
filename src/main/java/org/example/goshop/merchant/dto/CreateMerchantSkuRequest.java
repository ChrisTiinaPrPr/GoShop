package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

public record CreateMerchantSkuRequest(

        // Map 本身只做非空校验；键和值由 Service 手动校验
        @NotEmpty(message = "规格不能为空")
        Map<String, String> specs,

        @Positive(message = "SKU价格必须大于0")
        Long priceCent,

        @PositiveOrZero(message = "库存必须大于等于0")
        Integer stock
) {
}