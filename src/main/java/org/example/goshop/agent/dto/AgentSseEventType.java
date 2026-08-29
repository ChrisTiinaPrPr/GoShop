package org.example.goshop.agent.dto;

/**
 * Agent 流式响应事件类型。
 *
 * <p>前端只能根据这些公开事件更新页面。
 * 模型内部推理内容不能通过 SSE 返回。</p>
 */
public enum AgentSseEventType {

    /**
     * 本次 AgentRun 已创建。
     *
     * <p>通常是客户端收到的第一个事件。</p>
     */
    RUN_STARTED,

    /**
     * 助手文本增量。
     *
     * <p>前端按照接收顺序把 delta 追加到当前助手消息中。</p>
     */
    CONTENT_DELTA,

    /**
     * Agent 开始调用白名单工具。
     *
     * <p>只返回安全的工具名称和展示文案，
     * 不能返回完整参数、订单隐私或系统提示词。</p>
     */
    TOOL_STARTED,

    /**
     * 白名单工具调用结束。
     *
     * <p>表示工具是否成功以及安全的进度文案。商品和订单查询成功时
     * 可以附带服务端结构化展示卡片，但不能返回完整工具原始结果。</p>
     */
    TOOL_COMPLETED,

    /**
     * Agent 创建了需要用户确认的业务动作。
     *
     * <p>首期只会用于“确认加入购物车”。收到该事件并不代表
     * 已经执行加购，前端必须展示确认卡片。</p>
     */
    ACTION_REQUIRED,

    /**
     * 助手消息已完整生成并成功落库。
     *
     * <p>正常情况下这是一次运行的最后一个成功事件。</p>
     */
    MESSAGE_COMPLETED,

    /**
     * Agent 运行失败。
     *
     * <p>失败后不能伪造商品、库存或订单数据。</p>
     */
    RUN_FAILED
}
