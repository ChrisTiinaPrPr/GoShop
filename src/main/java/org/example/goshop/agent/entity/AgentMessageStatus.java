package org.example.goshop.agent.entity;

/**
 * Agent 可见消息的持久化状态。
 *
 * <p>用户消息创建后直接是 COMPLETED。助手消息需要经历流式生成，
 * 所以会先创建一条 STREAMING 状态的占位消息。</p>
 */
public enum AgentMessageStatus {

    /**
     * 助手消息正在生成。
     *
     * <p>该状态下的正文可能为空或不完整，不能放进下一轮模型上下文。</p>
     */
    STREAMING,

    /**
     * 消息已经完整保存。
     *
     * <p>只有 COMPLETED 状态的助手消息可以进入后续对话上下文。</p>
     */
    COMPLETED,

    /**
     * 助手消息生成失败。
     *
     * <p>失败消息可以保留错误提示用于页面展示，
     * 但不能作为有效助手回答发送给模型。</p>
     */
    FAILED
}
