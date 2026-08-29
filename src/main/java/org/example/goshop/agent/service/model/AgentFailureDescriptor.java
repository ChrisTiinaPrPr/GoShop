package org.example.goshop.agent.service.model;

/**
 * 可以安全写入数据库并通过 SSE 返回的错误描述。
 *
 * @param errorCode   内部错误分类，不包含异常正文
 * @param safeMessage 可以展示给买家的固定提示
 * @param retryable   用户稍后重试是否可能恢复
 * @param timedOut    是否应当把 AgentRun 收口为 TIMED_OUT
 */

public record AgentFailureDescriptor(
        String errorCode,
        String safeMessage,
        boolean retryable,
        boolean timedOut
) {
    public AgentFailureDescriptor {
        if (errorCode == null
                || errorCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent 错误分类不能为空"
            );
        }

        if (safeMessage == null
                || safeMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent 安全错误提示不能为空"
            );
        }
    }
}
