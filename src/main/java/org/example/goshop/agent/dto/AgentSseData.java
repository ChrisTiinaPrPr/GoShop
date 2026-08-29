package org.example.goshop.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SSE 事件 data 字段的公共类型。
 *
 * <p>使用 sealed interface 限制允许发送给前端的数据类型，
 * 避免开发时随意把模型原始响应、工具完整返回值或敏感信息
 * 塞进 SSE 数据中。</p>
 */
public sealed interface AgentSseData {

    /**
     * RUN_STARTED 的 data。
     *
     * @param userMessageId      已落库的用户消息 ID
     * @param assistantMessageId 助手占位消息 ID
     * @param replayed           是否由重复 clientMessageId 触发结果复用
     */
    record RunStartedData(
            Long userMessageId,
            Long assistantMessageId,
            boolean replayed
    ) implements AgentSseData {
    }

    /**
     * CONTENT_DELTA 的 data。
     *
     * @param delta 本次新增的纯文本片段，不是累计全文
     */
    record ContentDeltaData(
            String delta
    ) implements AgentSseData {
    }

    /**
     * TOOL_STARTED 的 data。
     *
     * <p>displayText 是给用户看的安全文案，例如“正在查询商品库存”。
     * 不能放工具参数 JSON。</p>
     */
    record ToolStartedData(
            String toolCallId,
            String toolName,
            String displayText
    ) implements AgentSseData {
    }

    /**
     * TOOL_COMPLETED 的 data。
     *
     * <p>除结果状态外，商品和订单查询可以附带服务端白名单映射后的
     * resultCard；仍然不能返回订单地址、手机号或完整工具原始结果。</p>
     */
    record ToolCompletedData(
            String toolCallId,
            String toolName,
            boolean success,
            String displayText,

            /**
             * 商品和订单查询成功时返回的安全展示卡片；其他工具或失败时为空。
             */
            AgentResultCardData resultCard
    ) implements AgentSseData {
    }

    /**
     * ACTION_REQUIRED 的 data。
     *
     * <p>首期用于展示“加入购物车确认卡片”。
     * 真正确认时前端只提交 actionId，不能重新提交或修改 skuId、数量。</p>
     */
    record ActionRequiredData(
            Long actionId,
            String actionType,
            String title,
            String description,
            Long productId,
            Long skuId,
            String skuName,
            Integer quantity,
            BigDecimal unitPrice,
            String imageUrl,
            Instant expiresAt
    ) implements AgentSseData {
    }

    /**
     * MESSAGE_COMPLETED 的 data。
     *
     * @param message 最终已经完成并落库的助手消息
     */
    record MessageCompletedData(
            AgentMessageResponse message
    ) implements AgentSseData {
    }

    /**
     * RUN_FAILED 的 data。
     *
     * @param code      对外错误码，例如 50301
     * @param message   可展示给买家的脱敏提示
     * @param retryable 当前错误是否适合由用户重新发送
     */
    record RunFailedData(
            int code,
            String message,
            boolean retryable
    ) implements AgentSseData {
    }
}
