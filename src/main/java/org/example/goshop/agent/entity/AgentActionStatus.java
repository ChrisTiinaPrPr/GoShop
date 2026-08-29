package org.example.goshop.agent.entity;

/**
 * Agent 待确认动作的状态。
 *
 * <p>允许的状态迁移：</p>
 *
 * <pre>
 * PENDING -> CONFIRMED
 * PENDING -> CANCELLED
 * PENDING -> EXPIRED
 * </pre>
 *
 * <p>任何终态都不能重新回到 PENDING。</p>
 */
public enum AgentActionStatus {

    /** 动作已经生成，正在等待用户确认或取消。 */
    PENDING,

    /** 用户已经确认，并且购物车写入成功。 */
    CONFIRMED,

    /** 用户主动取消动作。 */
    CANCELLED,

    /** 用户在有效期内没有操作，动作已经过期。 */
    EXPIRED
}
