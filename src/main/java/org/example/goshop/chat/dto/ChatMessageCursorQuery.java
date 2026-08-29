package org.example.goshop.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** 历史消息向前翻页或断线后向后补偿的游标参数。 */
@Schema(
        name = "ChatMessageCursorQuery",
        description = "消息游标查询；beforeMessageId 与 afterMessageId 最多填写一个"
)
public record ChatMessageCursorQuery(
        @Schema(
                description = "查询比该 ID 更早的消息，用于向上滚动历史记录",
                example = "2041290474313932800",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Positive(message = "beforeMessageId 必须是正数")
        Long beforeMessageId,

        @Schema(
                description = "查询比该 ID 更新的消息，用于 WebSocket 断线补偿",
                example = "2041286378101014528",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Positive(message = "afterMessageId 必须是正数")
        Long afterMessageId,

        @Schema(
                description = "返回数量，默认 30，最大 50",
                example = "30",
                minimum = "1",
                maximum = "50",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Min(value = 1, message = "消息查询数量不能小于 1")
        @Max(value = 50, message = "消息查询数量不能超过 50")
        Integer limit
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "beforeMessageId 与 afterMessageId 不能同时使用")
    public boolean isCursorDirectionValid() {
        return beforeMessageId == null || afterMessageId == null;
    }

    /** Controller/Service 统一使用该方法取得默认值，避免散落数字 30。 */
    @JsonIgnore
    @Schema(hidden = true)
    public int effectiveLimit() {
        return limit == null ? 30 : limit;
    }
}
