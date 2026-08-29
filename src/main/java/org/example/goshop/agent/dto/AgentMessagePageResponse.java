package org.example.goshop.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
/**
 * Agent 历史消息游标分页响应。
 *
 * <p>items 必须按消息 ID 升序排列，前端可以直接按顺序渲染。
 * Mapper 查询使用倒序是为了取得最近数据，Service 负责反转。</p>
 */
@Schema(
        name = "AgentMessagePageResponse",
        description = "Agent 历史消息游标分页结果"
)
public record AgentMessagePageResponse(
        @Schema(
                description = "按消息 ID 升序排列的消息",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<AgentMessageResponse> items,

        @Schema(
                description = "当前方向是否还有更早消息",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        boolean hasMore,

        @Schema(
                description = "本次结果中最早的消息 ID；结果为空时为 null",
                example = "2041290571319791616",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        Long oldestMessageId
) {
    /**
     * 根据已经按 ID 升序排列的消息创建分页响应。
     *
     * @param source  按 ID 升序排列的响应列表
     * @param hasMore 是否还有更早消息
     */
    public static AgentMessagePageResponse of(
            List<AgentMessageResponse> source,
            boolean hasMore
    ) {
        // 转换为不可变列表，防止返回后又被 Service 修改。
        List<AgentMessageResponse> items = source == null
                ? List.of()
                : List.copyOf(source);

        Long oldestMessageId = items.isEmpty()
                ? null
                : items.get(0).id();

        return new AgentMessagePageResponse(
                items,
                hasMore,
                oldestMessageId
        );
    }
}
