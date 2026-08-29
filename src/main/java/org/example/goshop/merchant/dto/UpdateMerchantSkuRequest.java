package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.*;

import java.util.Map;

public record UpdateMerchantSkuRequest(
        @Positive(message = "SKU id 必须大于 0")
        Long id, // 为空表示新增 SKU

        @NotEmpty(message = "规格不能为空")
        Map<String, String> specs,

        @Positive(message = "价格必须大于 0")
        Long priceCent,

        @PositiveOrZero(message = "库存数量必须大于等于 0")
        Integer stock,

        @Min(value = 0, message = "SKU 状态只能为 0 或 1")
        @Max(value = 1, message = "SKU 状态只能为 0 或 1")
        Integer status // 为空： 旧 SKU 保持原状态； 新 SKU 默认启用
) {
}
