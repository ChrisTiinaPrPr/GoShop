package org.example.goshop.merchant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreateMerchantProductRequest(

        @NotBlank(message = "商品标题不能为空")
        @Size(max = 200, message = "商品标题长度不能超过200个字符")
        String title,

        @Size(max = 10000, message = "商品描述长度不能超过10000个字符")
        String description,

        // 必须是当前商家的启用店内分类
        @NotNull(message = "商品分类不能为空")
        @Positive(message = "商品分类ID必须是正数")
        Long categoryId,

        @NotEmpty(message = "至少需要创建一个 SKU")
        @Size(max = 50, message = "商品 SKU 数量不能超过50个")
        List<@Valid CreateMerchantSkuRequest> skus
) {
}
