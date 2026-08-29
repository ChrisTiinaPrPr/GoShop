package org.example.goshop.agent.service.model;

import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.entity.AgentRun;

import java.util.Objects;
/**
 * AgentRun 初始化事务的完整结果。
 *
 * <p>创建新运行和恢复幂等请求都会返回这个对象。后续模型编排层
 * 不需要再次分别查询用户消息、助手消息和运行记录。</p>
 *
 * @param userId           当前 JWT 买家 ID
 * @param userMessage      已完成落库的用户消息
 * @param assistantMessage 助手占位消息或已经完成的助手消息
 * @param run              与两条消息对应的运行记录
 * @param replayed         是否命中了已有 clientMessageId
 */

public record AgentRunInitialization(
        Long userId,
        AgentMessage userMessage,
        AgentMessage assistantMessage,
        AgentRun run,
        boolean replayed
) {
    /**
     * 防止事务 Service 返回结构不完整的初始化结果。
     */
    public AgentRunInitialization {

        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "AgentRun 用户 ID 必须是正整数"
            );
        }

        Objects.requireNonNull(
                userMessage,
                "用户消息不能为空"
        );
        Objects.requireNonNull(
                assistantMessage,
                "助手消息不能为空"
        );
        Objects.requireNonNull(
                run,
                "AgentRun 不能为空"
        );
    }

    /**
     * 是否是本次请求新创建的运行。
     *
     * <p>只有新运行才应该真正调用一次模型。幂等重试不能再次调用。</p>
     */
    public boolean newlyCreated() {
        return !replayed;
    }
}
