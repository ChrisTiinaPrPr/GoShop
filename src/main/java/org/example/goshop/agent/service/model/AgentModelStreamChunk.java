package org.example.goshop.agent.service.model;

/**
 * 模型流中的一个响应分片。
 *
 * <p>文本和 Token 用量不一定出现在同一个分片中。OpenAI-compatible
 * 服务通常先返回正文增量，最后再返回一个只有 Usage 的结束分片。因此
 * 编排层不能因为正文为空就丢弃整个分片。</p>
 *
 * <p>responseId 用于区分 Tool Calling 过程中多轮独立模型请求。同一
 * responseId 可能重复返回累计 Usage，编排层应覆盖旧快照；不同 ID
 * 才表示需要相加的独立请求。</p>
 *
 * @param contentDelta 本次新增的正文；没有正文时为空字符串
 * @param responseId 供应商响应 ID；未提供时为空
 * @param promptTokens 供应商返回的输入 Token 数
 * @param completionTokens 供应商返回的输出 Token 数
 */
public record AgentModelStreamChunk(
        String contentDelta,
        String responseId,
        Integer promptTokens,
        Integer completionTokens
) {

    public AgentModelStreamChunk {
        /*
         * Reactor 不允许流中出现 null，因此把“无正文”统一表示为空字符串。
         * 这里不能 trim，单独的空格和换行也可能是回答格式的一部分。
         */
        contentDelta = contentDelta == null
                ? ""
                : contentDelta;

        responseId = responseId == null
                || responseId.isBlank()
                ? null
                : responseId.strip();

        requireNonNegative(
                promptTokens,
                "输入 Token 数"
        );
        requireNonNegative(
                completionTokens,
                "输出 Token 数"
        );
    }

    /**
     * 兼容只需要正文和 Usage、没有响应 ID 的调用方。
     */
    public AgentModelStreamChunk(
            String contentDelta,
            Integer promptTokens,
            Integer completionTokens
    ) {
        this(
                contentDelta,
                null,
                promptTokens,
                completionTokens
        );
    }

    /**
     * 创建没有模型 Usage 的纯文本分片。
     *
     * <p>服务端确定性编排结果使用该方法。它没有调用模型，不能伪造
     * Prompt/Completion Token 数。</p>
     */
    public static AgentModelStreamChunk textOnly(
            String contentDelta
    ) {
        return new AgentModelStreamChunk(
                contentDelta,
                null,
                null,
                null
        );
    }

    /**
     * 当前分片是否包含需要推送给前端的正文。
     */
    public boolean hasContent() {
        return !contentDelta.isEmpty();
    }

    /**
     * 当前分片是否包含供应商 Usage。
     */
    public boolean hasUsage() {
        return promptTokens != null
                || completionTokens != null;
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
