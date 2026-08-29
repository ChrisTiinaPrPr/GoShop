package org.example.goshop.agent.entity;

/**
 * 一次 Agent 模型运行的状态。
 *
 * <p>一次运行从用户消息开始，经过模型生成、工具调用，
 * 最终形成一条完整助手消息或失败记录。</p>
 */
public enum AgentRunStatus {

    /**
     * 模型正在生成或调用工具。
     *
     * <p>数据库通过生成列和唯一索引保证：
     * 同一个会话最多存在一个 RUNNING 运行。</p>
     */
    RUNNING,

    /** 模型成功生成完整回答，助手消息也已更新为 COMPLETED。 */
    COMPLETED,

    /** 模型调用或工具执行发生普通失败。 */
    FAILED,

    /** 运行超过配置的总超时时间。 */
    TIMED_OUT
}
