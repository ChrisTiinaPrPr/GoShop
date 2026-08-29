package org.example.goshop.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建商品评价请求。
 *
 * <p>客户端只提交订单项、评分和文字。userId、spuId 与订单状态全部由服务端查询，
 * 防止伪造已购商品评价或冒用其他买家身份。</p>
 */
public record CreateProductReviewRequest(
        @NotNull(message = "订单项 ID 不能为空")
        @Positive(message = "订单项 ID 必须为正数")
        Long orderItemId,

        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最低为 1 星")
        @Max(value = 5, message = "评分最高为 5 星")
        Integer score,

        @Size(max = 1000, message = "评价内容不能超过 1000 个字符")
        String content
) {
}
