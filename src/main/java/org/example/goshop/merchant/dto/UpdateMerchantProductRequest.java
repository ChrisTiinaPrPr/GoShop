package org.example.goshop.merchant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateMerchantProductRequest(
        @Size(min = 1, max = 200, message = "商品标题长度必须在 1 到 200 个字符之间")
        String title,

        @Size(max = 10000, message = "商品描述长度不能超过 10000 个字符")
        String description, // 传空字符串可清空描述

        @Positive(message = "商品分类 ID 必须为正数")
        Long categoryId,

        @Min(value = 0, message = "商品状态只能为 0 或 1")
        @Max(value = 1, message = "商品状态只能为 0 或 1")
        Integer status,

        List<@Valid UpdateMerchantSkuRequest> skus
) {
}
