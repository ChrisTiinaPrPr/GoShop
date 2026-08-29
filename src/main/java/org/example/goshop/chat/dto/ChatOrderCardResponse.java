package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** ORDER 消息的脱敏订单摘要。 */
@Schema(
        name = "ChatOrderCardResponse",
        description = "订单卡片公开摘要；不返回收货人、手机号和地址快照"
)
public record ChatOrderCardResponse(
        @Schema(description = "订单号", example = "2041286378101014528",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        String orderNo,

        @Schema(description = "首件商品购买时标题快照", example = "极光 K87 三模机械键盘", maxLength = 200,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 200)
        String productTitle,

        @Schema(description = "首件商品购买时图片快照", maxLength = 512,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 512)
        String productImage,

        @Schema(description = "订单中的商品种类数", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Integer productTypeCount,

        @Schema(description = "实付金额，单位分", example = "29900", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @PositiveOrZero
        Long payAmountCent,

        @Schema(description = "最新订单状态", example = "WAITING_SHIPMENT", maxLength = 32,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        String status
) {
}
