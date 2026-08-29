package org.example.goshop.refund.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRefundRequest(
        @Schema(
                description = "退款原因",
                example = "商品与描述不符"
        )
        @NotBlank(message = "退款原因不能为空")
        @Size(
                min = 2,
                max = 255,
                message = "退款原因长度必须在 2 到 255 个字符之间"
        )
        String reason
) {
}
