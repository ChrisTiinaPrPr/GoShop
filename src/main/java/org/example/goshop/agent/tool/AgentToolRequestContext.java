package org.example.goshop.agent.tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Objects;

/**
 * 一次 AgentRun 内部工具调用的可信上下文。
 *
 * <p>userId、conversationId 和 runId 都由服务端创建，
 * 绝对不能作为 @ToolParam 暴露给模型。</p>
 *
 * <p>模型只能生成商品关键词、规则主题、订单号等业务参数，
 * 不能决定自己以哪个用户身份调用工具。</p>
 */
public record AgentToolRequestContext(
        Long userId,
        Long conversationId,
        Long runId,
        AgentToolEventChannel eventChannel
) {
    /** ToolContext 中的固定字段名。 */
    private static final String USER_ID_KEY = "userId";
    private static final String CONVERSATION_ID_KEY = "conversationId";
    private static final String RUN_ID_KEY = "runId";
    private static final String EVENT_CHANNEL_KEY = "agentToolEventChannel";

    /**
     * 所有 ID 都必须来自已经完成鉴权和归属校验的服务端流程。
     */
    public AgentToolRequestContext {
        requirePositive(userId, USER_ID_KEY);
        requirePositive(
                conversationId,
                CONVERSATION_ID_KEY
        );
        requirePositive(runId, RUN_ID_KEY);

        Objects.requireNonNull(
                eventChannel,
                "Agent 工具事件通道不能为空"
        );

        if (!conversationId.equals(
                eventChannel.conversationId()
        )) {
            throw new IllegalArgumentException(
                    "工具上下文与事件通道的 conversationId 不一致"
            );
        }

        if (!runId.equals(eventChannel.runId())) {
            throw new IllegalArgumentException(
                    "工具上下文与事件通道的 runId 不一致"
            );
        }
    }

    /**
     * 转换成 Spring AI 单次模型请求需要的 ToolContext Map。
     *
     * <p>该 Map 不会作为工具 JSON Schema 发送给模型，
     * 只会在服务端执行工具方法时注入。</p>
     */
    public Map<String, Object> toMap() {
        return Map.of(
                USER_ID_KEY, userId,
                CONVERSATION_ID_KEY, conversationId,
                RUN_ID_KEY, runId,
                EVENT_CHANNEL_KEY, eventChannel
        );
    }

    /**
     * 在具体工具方法中恢复可信上下文。
     *
     * <p>每个工具都应调用该方法。即使某个工具暂时不需要 userId，
     * 也要确保它只能在合法 AgentRun 中执行。</p>
     */
    public static AgentToolRequestContext from(
            // ToolContext 保存 userId,conversationId,runId
            ToolContext toolContext
    ) {
        Objects.requireNonNull(
                toolContext,
                "Agent 工具缺少 ToolContext"
        );

        Map<String, Object> context =
                toolContext.getContext();

        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent 工具上下文不能为空"
            );
        }

        // 需要将 Object 转换为 Long
        return new AgentToolRequestContext(
                readLong(context, USER_ID_KEY),
                readLong(context, CONVERSATION_ID_KEY),
                readLong(context, RUN_ID_KEY),
                readEventChannel(context)
        );
    }

    /**
     * 只接受服务端放入的 Number，不接受字符串转换。
     *
     * <p>这样可以防止调用方把未经验证的请求参数直接放进上下文。</p>
     */
    private static Long readLong(
            Map<String, Object> context,
            String key
    ) {
        Object value = context.get(key);

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "Agent 工具上下文缺少字段：" + key
            );
        }

        return number.longValue();
    }

    /**
     * 读取服务端放入 ToolContext 的事件通道。
     *
     * <p>不接受 Map 或字符串转换，只接受实际的
     * AgentToolEventChannel 实例。</p>
     */
    private static AgentToolEventChannel
    readEventChannel(
            Map<String, Object> context
    ) {
        Object value =
                context.get(EVENT_CHANNEL_KEY);

        if (!(value instanceof
                AgentToolEventChannel channel)) {
            throw new IllegalArgumentException(
                    "Agent 工具上下文缺少事件通道"
            );
        }

        return channel;
    }

    private static void requirePositive(
            Long value,
            String fieldName
    ) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(
                    fieldName + " 必须是正整数"
            );
        }
    }
}
