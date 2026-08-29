package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateMerchantProductStatusRequest(
        @NotNull(message = "商品状态不能为空")
        @Min(value = 0,message = "商品状态只能为0或1")
        @Max(value = 1,message = "商品状态只能为0或1")
        Integer status // 0:下架 1:上架
) {
}
