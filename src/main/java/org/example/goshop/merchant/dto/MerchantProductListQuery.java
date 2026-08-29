package org.example.goshop.merchant.dto;

public record MerchantProductListQuery(
        Long merchantId,
        Long categoryId,
        String keyword,
        Integer status,

        // 仅有服务端枚举生成，避免前端拼接排序
        String sort
) {
}
