package org.example.goshop.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
/**
 * Agent 历史消息游标查询参数。
 *
 * <p>Agent 历史接口目前只需要向前加载更早消息，
 * 因此只使用 beforeMessageId，不提供 afterMessageId。</p>
 */
@Schema(
        name = "AgentMessageCursorQuery",
        description = "Agent 历史消息游标查询参数"
)
public record AgentMessageCursorQuery(
        @Schema(
                description = "查询该消息 ID 之前的历史消息；首次加载不传",
                example = "2041290571319791616",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Positive(message = "beforeMessageId 必须是正数")
        Long beforeMessageId,

        @Schema(
                description = "返回数量，默认 30，最大 50",
                example = "30",
                minimum = "1",
                maximum = "50",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Min(value = 1, message = "Agent 消息查询数量不能小于 1")
        @Max(value = 50, message = "Agent 消息查询数量不能超过 50")
        Integer limit
) {
    /**
     * 返回经过默认值处理的 limit。
     *
     * <p>Controller 和 Service 统一调用此方法，避免多个位置分别写默认值 30。</p>
     */
    @JsonIgnore
    @Schema(hidden = true)
    public int effectiveLimit() {
        return limit == null ? 30 : limit;
    }
}
