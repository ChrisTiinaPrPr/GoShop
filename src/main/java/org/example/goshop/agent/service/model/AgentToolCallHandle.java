package org.example.goshop.agent.service.model;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 一次工具执行的服务端句柄。
 *
 * <p>开始审计后返回该对象，工具完成时再把它交给审计 Service。
 * startedNanoTime 只用于计算耗时，不写入数据库。</p>
 *
 * @param auditId        agent_tool_call 主键
 * @param runId          所属 AgentRun
 * @param toolCallId     SSE 和审计记录使用的关联 UUID
 * @param toolName       固定白名单工具名
 * @param startedNanoTime 工具开始时的单调时钟值
 */
public record AgentToolCallHandle(
        Long auditId,
        Long runId,
        String toolCallId,
        String toolName,
        long startedNanoTime
) {
    public AgentToolCallHandle {
        requirePositive(auditId, "auditId");
        requirePositive(runId, "runId");

        Objects.requireNonNull(
                toolCallId,
                "toolCallId 不能为空"
        );
        Objects.requireNonNull(
                toolName,
                "toolName 不能为空"
        );

        if (toolCallId.isBlank()
                || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "工具调用标识和名称不能为空"
            );
        }

        if (startedNanoTime <= 0) {
            throw new IllegalArgumentException(
                    "工具开始时间不合法"
            );
        }
    }

    /**
     * 计算工具执行耗时。
     *
     * <p>使用 System.nanoTime()，不受系统时间校准影响。
     * 数据库字段为 INT，因此需要限制最大值。</p>
     */
    public int durationMs() {
        long elapsedNanos = Math.max(
                0L,
                System.nanoTime() - startedNanoTime
        );

        long elapsedMs =
                TimeUnit.NANOSECONDS.toMillis(
                        elapsedNanos
                );

        return (int) Math.min(
                Integer.MAX_VALUE,
                elapsedMs
        );
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
