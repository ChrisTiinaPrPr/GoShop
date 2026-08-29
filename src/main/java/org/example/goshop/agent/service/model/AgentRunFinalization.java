package org.example.goshop.agent.service.model;

import org.example.goshop.agent.dto.AgentMessageResponse;
import org.example.goshop.agent.entity.AgentRunStatus;

import java.util.Objects;
/**
 * AgentRun 成功、失败或超时后的最终状态。
 *
 * @param status           最终运行状态
 * @param assistantMessage 最终助手消息
 * @param stateChanged     本次调用是否实际修改了数据库
 * @param errorCode        脱敏错误分类；成功时为空
 */
public record AgentRunFinalization(
        AgentRunStatus status,
        AgentMessageResponse assistantMessage,
        boolean stateChanged,
        String errorCode
) {

    public AgentRunFinalization {
        Objects.requireNonNull(
                status,
                "最终运行状态不能为空"
        );
        Objects.requireNonNull(
                assistantMessage,
                "最终助手消息不能为空"
        );

        /*
         * 收口结果不允许仍是 RUNNING。
         */
        if (status == AgentRunStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "收口结果不能是 RUNNING"
            );
        }
    }
}
