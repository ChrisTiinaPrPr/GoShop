package org.example.goshop.agent.service.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 一次模型流的文本、Token 和耗时累加器。
 *
 * <p>模型每返回一个响应分片，编排层会调用 accept()；确定性纯文本
 * 场景也可以继续调用 append()。流结束后再从这里取得完整回答和
 * 运行指标。</p>
 *
 * <p>使用 System.nanoTime() 计算耗时，因为它是单调时钟，
 * 不会受到操作系统校时或时区变化影响。</p>
 */
public final class AgentStreamAccumulator {

    /** 开始执行本次 Agent 流时的单调时钟。 */
    private final long startedNanos =
            System.nanoTime();

    /** 第一个非空文本增量到达时的单调时钟。 */
    private Long firstTokenNanos;

    /** 按模型返回顺序拼接完整回答。 */
    private final StringBuilder content =
            new StringBuilder();

    /**
     * 按供应商响应 ID 保存各轮模型请求的最新 Usage 快照。
     *
     * <p>Tool Calling 会在同一个 AgentRun 中发起多轮模型请求，每轮通常
     * 有不同 responseId，因此最终需要跨 ID 相加。</p>
     */
    private final Map<String, UsageSnapshot>
            usageByResponseId = new LinkedHashMap<>();

    /** 兼容供应商未提供响应 ID 时的最近 Usage 快照。 */
    private UsageSnapshot anonymousUsage;

    /**
     * 接收一个完整模型响应分片。
     *
     * <p>Usage 分片可能没有正文，因此 Token 更新与 append 必须分别
     * 执行。同一个 responseId 的 Usage 是累计快照，只覆盖旧值；Tool
     * Calling 产生不同 responseId 时，各轮快照会在 metrics() 中相加。</p>
     */
    public synchronized void accept(
            AgentModelStreamChunk chunk
    ) {
        if (chunk == null) {
            return;
        }

        append(chunk.contentDelta());

        if (chunk.hasUsage()) {
            recordUsage(chunk);
        }
    }

    /**
     * 追加一个模型文本增量。
     *
     * <p>不对 delta 执行 trim 或 strip，因为空格和换行可能是
     * 最终回答格式的一部分。</p>
     */
    public synchronized void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        if (firstTokenNanos == null) {
            firstTokenNanos = System.nanoTime();
        }

        content.append(delta);
    }

    /**
     * 返回当前已经累计的完整正文副本。
     */
    public synchronized String content() {
        return content.toString();
    }

    /**
     * 当前运行总耗时。
     *
     * <p>失败和超时时也可以调用该方法。</p>
     */
    public synchronized int durationMs() {
        return nanosToIntMillis(
                System.nanoTime() - startedNanos
        );
    }

    /**
     * 构造成功运行指标。
     *
     * <p>Token 数直接来自供应商 Usage。兼容供应商未返回 Usage，或本轮
     * 走的是不调用模型的确定性编排时，对应字段保持为空。</p>
     */
    public synchronized AgentRunMetrics metrics() {
        Integer firstTokenMs = null;

        if (firstTokenNanos != null) {
            firstTokenMs = nanosToIntMillis(
                    firstTokenNanos - startedNanos
            );
        }

        UsageSnapshot totalUsage = totalUsage();

        return new AgentRunMetrics(
                totalUsage == null
                        ? null
                        : totalUsage.promptTokens(),
                totalUsage == null
                        ? null
                        : totalUsage.completionTokens(),
                firstTokenMs,
                durationMs()
        );
    }

    /**
     * 保存一个响应的最新 Usage 快照。
     */
    private void recordUsage(
            AgentModelStreamChunk chunk
    ) {
        UsageSnapshot incoming = new UsageSnapshot(
                chunk.promptTokens(),
                chunk.completionTokens()
        );

        if (chunk.responseId() == null) {
            anonymousUsage = mergeUsage(
                    anonymousUsage,
                    incoming
            );
            return;
        }

        usageByResponseId.compute(
                chunk.responseId(),
                (ignored, existing) ->
                        mergeUsage(existing, incoming)
        );
    }

    /**
     * 合并同一个供应商响应的部分 Usage 字段。
     *
     * <p>字段有值时使用新快照；字段缺失时保留该响应之前的值。</p>
     */
    private UsageSnapshot mergeUsage(
            UsageSnapshot existing,
            UsageSnapshot incoming
    ) {
        if (existing == null) {
            return incoming;
        }

        return new UsageSnapshot(
                incoming.promptTokens() == null
                        ? existing.promptTokens()
                        : incoming.promptTokens(),
                incoming.completionTokens() == null
                        ? existing.completionTokens()
                        : incoming.completionTokens()
        );
    }

    /**
     * 汇总一次 AgentRun 中所有独立模型响应。
     *
     * <p>只要供应商提供了 responseId，就以按 ID 聚合的快照为准；匿名
     * 快照只作为完全没有 ID 的兼容供应商降级路径，避免同一请求的有名
     * 和匿名分片被重复相加。</p>
     */
    private UsageSnapshot totalUsage() {
        if (usageByResponseId.isEmpty()) {
            return anonymousUsage;
        }

        Integer totalPromptTokens = null;
        Integer totalCompletionTokens = null;

        for (UsageSnapshot usage
             : usageByResponseId.values()) {
            totalPromptTokens = saturatingAdd(
                    totalPromptTokens,
                    usage.promptTokens()
            );
            totalCompletionTokens = saturatingAdd(
                    totalCompletionTokens,
                    usage.completionTokens()
            );
        }

        return new UsageSnapshot(
                totalPromptTokens,
                totalCompletionTokens
        );
    }

    /**
     * Token 字段使用 Integer，极端异常值按上限饱和，避免溢出为负数。
     */
    private Integer saturatingAdd(
            Integer current,
            Integer increment
    ) {
        if (increment == null) {
            return current;
        }

        if (current == null) {
            return increment;
        }

        long sum = (long) current + increment;

        return (int) Math.min(
                Integer.MAX_VALUE,
                sum
        );
    }

    /**
     * 把纳秒安全转换成数据库使用的 Integer 毫秒。
     */
    private int nanosToIntMillis(long nanos) {
        long nonNegativeNanos =
                Math.max(0L, nanos);

        long millis =
                TimeUnit.NANOSECONDS.toMillis(
                        nonNegativeNanos
                );

        return (int) Math.min(
                Integer.MAX_VALUE,
                millis
        );
    }

    /**
     * 单个供应商响应的 Token 快照。
     */
    private record UsageSnapshot(
            Integer promptTokens,
            Integer completionTokens
    ) {
    }
}
