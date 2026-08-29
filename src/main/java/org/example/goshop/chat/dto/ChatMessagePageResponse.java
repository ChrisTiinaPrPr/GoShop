package org.example.goshop.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 聊天消息游标分页结果。
 *
 * <p>无论使用 beforeMessageId 查询历史，还是使用 afterMessageId 进行断线补偿，
 * items 最终都按消息 ID 从小到大排列。前端可以直接依次追加或插入，不需要猜测排序方向。</p>
 */
@Schema(name = "ChatMessagePageResponse", description = "聊天消息游标分页结果")
public record ChatMessagePageResponse(

        @Schema(description = "按消息 ID 升序排列的消息")
        List<ChatMessageResponse> items,

        @Schema(description = "当前查询方向是否还有更多消息")
        boolean hasMore,

        @Schema(description = "本次最早消息 ID；结果为空时为 null")
        Long oldestMessageId,

        @Schema(description = "本次最新消息 ID；结果为空时为 null")
        Long newestMessageId
) {
    /**
     * 通过消息列表创建分页结果，统一计算前后游标。
     *
     * @param source  按消息 ID 升序排列的消息
     * @param hasMore 当前查询方向是否还有更多数据
     */
    public static ChatMessagePageResponse of(
            List<ChatMessageResponse> source,
            boolean hasMore
    ) {
        List<ChatMessageResponse> items = source == null ? List.of() : List.copyOf(source);

        if (items.isEmpty()) {
            return new ChatMessagePageResponse(
                    items,
                    hasMore,
                    null,
                    null
            );
        }
        return new ChatMessagePageResponse(
                items,
                hasMore,
                items.get(0).id(),
                items.get(items.size() - 1).id()
        );
    }
}
