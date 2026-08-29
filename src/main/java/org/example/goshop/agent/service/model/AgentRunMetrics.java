package org.example.goshop.agent.service.model;

/**
 * 一次成功模型调用的可观测指标。
 *
 * <p>Token 数可能因为兼容供应商未返回 Usage 而为空；
 * durationMs 由应用自己计时，因此必须有值。</p>
 */
public record AgentRunMetrics(
        Integer promptTokens,
        Integer completionTokens,
        Integer firstTokenMs,
        int durationMs
) {

    public AgentRunMetrics {
        requireNonNegative(promptTokens, "输入 Token 数");
        requireNonNegative(completionTokens, "输出 Token 数");
        requireNonNegative(firstTokenMs, "首字延迟");

        if (durationMs < 0) {
            throw new IllegalArgumentException(
                    "运行总耗时不能是负数"
            );
        }
    }

    private static void requireNonNegative(
            Integer value,
            String fieldName
    ) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(
                    fieldName + "不能是负数"
            );
        }
    }
}
