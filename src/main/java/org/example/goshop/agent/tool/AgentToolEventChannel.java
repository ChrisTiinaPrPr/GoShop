package org.example.goshop.agent.tool;

import org.example.goshop.agent.dto.AgentSseData;
import org.example.goshop.agent.dto.AgentSseEvent;
import org.example.goshop.agent.dto.AgentSseEventType;
import org.example.goshop.agent.dto.AgentResultCardData;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Objects;

/**
 * 单次 AgentRun 使用的工具进度事件通道。
 * 把工具调用的实时状态合并进 SSE 事件流
 *
 * <p>Spring AI 会在模型流内部同步执行工具，而 Controller 最终需要
 * 输出统一的 SSE 事件流。因此需要通过这个通道把工具执行线程产生的
 * TOOL_STARTED、TOOL_COMPLETED 事件送回主编排流。</p>
 *
 * <p>每个 AgentRun 都必须创建独立通道，禁止在多个运行之间共享，
 * 否则可能把一个用户的工具进度发送给另一个用户。</p>
 */
public final class AgentToolEventChannel {

    private final Long conversationId;
    private final Long runId;

    /**
     * unicast 表示只允许主编排流订阅一次。
     *
     * <p>onBackpressureBuffer 允许工具在主编排完成订阅前短暂发送事件，
     * 事件会先进入内存队列，不会直接丢失。</p>
     */
    private final Sinks.Many<AgentSseEvent<?>>
            sink = Sinks.many()
            .unicast()
            .onBackpressureBuffer();

    /**
     * 由 synchronized 保护，防止完成后继续写入事件。
     */
    private boolean closed;

    public AgentToolEventChannel(
            Long conversationId,
            Long runId
    ) {
        requirePositive(
                conversationId,
                "conversationId"
        );
        requirePositive(runId, "runId");

        this.conversationId = conversationId;
        this.runId = runId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long runId() {
        return runId;
    }

    /**
     * 返回工具事件 Flux。
     *
     * <p>这里只返回冷的订阅视图。真正事件由 publishStarted()
     * 和 publishCompleted() 写入。</p>
     */
    public Flux<AgentSseEvent<?>> events() {
        return sink.asFlux();
    }

    /**
     * 发布工具开始事件。
     *
     * <p>只能发送固定安全展示文案，不能把 arguments JSON
     * 或用户隐私放入 displayText。</p>
     */
    public void publishStarted(
            String toolCallId,
            String toolName,
            String displayText
    ) {
        validateEventFields(
                toolCallId,
                toolName,
                displayText
        );

        emit(
                AgentSseEvent.create(
                        AgentSseEventType.TOOL_STARTED,
                        conversationId,
                        runId,
                        new AgentSseData.ToolStartedData(
                                toolCallId,
                                toolName,
                                displayText
                        )
                )
        );
    }

    /**
     * 发布工具完成事件。
     *
     * @param success true 表示工具成功；false 表示工具失败
     */
    public void publishCompleted(
            String toolCallId,
            String toolName,
            boolean success,
            String displayText
    ) {
        publishCompleted(
                toolCallId,
                toolName,
                success,
                displayText,
                null
        );
    }

    /**
     * 发布工具完成事件，并可附带服务端生成的结构化结果卡片。
     */
    public void publishCompleted(
            String toolCallId,
            String toolName,
            boolean success,
            String displayText,
            AgentResultCardData resultCard
    ) {
        validateEventFields(
                toolCallId,
                toolName,
                displayText
        );

        if (!success && resultCard != null) {
            throw new IllegalArgumentException(
                    "失败的工具调用不能携带结果卡片"
            );
        }

        if (resultCard != null
                && !toolCallId.equals(resultCard.toolCallId())) {
            throw new IllegalArgumentException(
                    "结果卡片与工具调用 ID 不一致"
            );
        }

        emit(
                AgentSseEvent.create(
                        AgentSseEventType.TOOL_COMPLETED,
                        conversationId,
                        runId,
                        new AgentSseData.ToolCompletedData(
                                toolCallId,
                                toolName,
                                success,
                                displayText,
                                resultCard
                        )
                )
        );
    }

    /**
     * 发布需要用户确认的业务动作。
     *
     * <p>首期只能发送 ADD_CART_ITEM。该事件只是要求前端展示确认卡片，
     * 发布事件本身不能修改购物车。</p>
     *
     * <p>data 中的商品、SKU、数量和价格必须来自已经保存的
     * agent_action 服务端快照，不能直接使用模型生成的展示字段。</p>
     */
    public void publishActionRequired(
            AgentSseData.ActionRequiredData data
    ) {
        Objects.requireNonNull(
                data,
                "Agent 动作确认事件不能为空"
        );

        /*
         * actionId 是前端确认和取消接口使用的唯一标识。
         */
        requirePositive(
                data.actionId(),
                "actionId"
        );

        /*
         * 首期动作白名单只有 ADD_CART_ITEM。
         *
         * 不允许模型或调用方通过事件通道临时增加下单、
         * 支付、退款或确认收货等高风险动作。
         */
        if (!"ADD_CART_ITEM".equals(
                data.actionType()
        )) {
            throw new IllegalArgumentException(
                    "不支持的 Agent 动作类型"
            );
        }

        requireText(
                data.title(),
                "动作卡片标题"
        );

        requireText(
                data.description(),
                "动作卡片描述"
        );

        if (data.title().length() > 100) {
            throw new IllegalArgumentException(
                    "动作卡片标题不能超过 100 字"
            );
        }

        /*
         * description 通常是商品标题。
         * 商品标题是不可信业务文本，因此必须限制长度。
         */
        if (data.description().length() > 200) {
            throw new IllegalArgumentException(
                    "动作卡片描述不能超过 200 字"
            );
        }

        requirePositive(
                data.productId(),
                "productId"
        );

        requirePositive(
                data.skuId(),
                "skuId"
        );

        requireText(
                data.skuName(),
                "SKU 展示名称"
        );

        if (data.skuName().length() > 500) {
            throw new IllegalArgumentException(
                    "SKU 展示名称不能超过 500 字"
            );
        }

        if (data.quantity() == null
                || data.quantity() <= 0
                || data.quantity() > 99) {
            throw new IllegalArgumentException(
                    "动作商品数量必须在 1～99 之间"
            );
        }

        /*
         * ActionRequiredData 中的 unitPrice 使用“元”，
         * 后续由工具把数据库的分转换成 BigDecimal 元。
         */
        if (data.unitPrice() == null
                || data.unitPrice().signum() < 0) {
            throw new IllegalArgumentException(
                    "动作商品价格不能为负数"
            );
        }

        if (data.imageUrl() != null
                && data.imageUrl().length() > 1000) {
            throw new IllegalArgumentException(
                    "动作商品图片地址过长"
            );
        }

        Instant expiresAt =
                Objects.requireNonNull(
                        data.expiresAt(),
                        "动作过期时间不能为空"
                );

        /*
         * 不发送创建时已经过期的动作。
         *
         * 正常动作默认有 10 分钟有效期，因此不会触碰该边界。
         */
        if (!expiresAt.isAfter(
                Instant.now()
        )) {
            throw new IllegalArgumentException(
                    "不能发布已经过期的 Agent 动作"
            );
        }

        emit(
                AgentSseEvent.create(
                        AgentSseEventType.ACTION_REQUIRED,
                        conversationId,
                        runId,
                        data
                )
        );

    }

    /**
     * 模型流结束后关闭工具事件通道。
     *
     * <p>该方法允许重复调用，第一次调用之后均直接返回。
     * 通道必须完成，否则 Flux.merge() 会一直等待，最终无法发送
     * MESSAGE_COMPLETED。</p>
     */
    public synchronized void complete() {
        if (closed) {
            return;
        }

        closed = true;

        Sinks.EmitResult result =
                sink.tryEmitComplete();

        if (result != Sinks.EmitResult.OK
                && result
                != Sinks.EmitResult.FAIL_TERMINATED) {
            throw new IllegalStateException(
                    "关闭 Agent 工具事件通道失败："
                            + result
            );
        }
    }

    /**
     * 串行写入事件。
     *
     * <p>当前 Spring AI 默认依次执行工具，但这里仍使用 synchronized
     * 防止未来改成并行工具调用后出现 FAIL_NON_SERIALIZED。</p>
     */
    private synchronized void emit(
            AgentSseEvent<?> event
    ) {
        if (closed) {
            throw new IllegalStateException(
                    "Agent 工具事件通道已经关闭"
            );
        }

        Sinks.EmitResult result =
                sink.tryEmitNext(event);

        if (result != Sinks.EmitResult.OK) {
            throw new IllegalStateException(
                    "发布 Agent 工具事件失败："
                            + result
            );
        }
    }

    /**
     * 校验 SSE 中允许公开的工具字段。
     */
    private void validateEventFields(
            String toolCallId,
            String toolName,
            String displayText
    ) {
        requireText(
                toolCallId,
                "toolCallId"
        );
        requireText(toolName, "toolName");
        requireText(
                displayText,
                "displayText"
        );

        /*
         * displayText 只是简短状态文案。
         * 限制长度可以防止未来误把工具结果塞入事件。
         */
        if (displayText.length() > 100) {
            throw new IllegalArgumentException(
                    "工具展示文案不能超过 100 字"
            );
        }
    }

    private void requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " 不能为空"
            );
        }
    }

    private void requirePositive(
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
