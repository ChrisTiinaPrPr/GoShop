package org.example.goshop.merchant.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 商家智能导购 RAG 解析与异步执行参数。
 *
 * @param enabled           文档解析和向量化总开关
 * @param chunkSize         单个分片的目标 Token 数
 * @param minChunkChars     非末尾分片的最小字符数
 * @param minEmbedChars     允许送入 Embedding 的最小字符数
 * @param maxChunks         单份文档最多产生的分片数
 * @param maxExtractedChars 单份文档允许提取的最大字符数
 * @param processingTimeout PROCESSING 状态被视为过期的时间
 * @param corePoolSize      解析线程池常驻线程数
 * @param maxPoolSize       解析线程池最大线程数
 * @param queueCapacity     等待解析任务队列容量
 */
@ConfigurationProperties(prefix = "goshop.merchant-ai.rag")
public record MerchantAiRagProperties(
        boolean enabled,
        int chunkSize,
        int minChunkChars,
        int minEmbedChars,
        int maxChunks,
        int maxExtractedChars,
        Duration processingTimeout,
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity
) {

    public MerchantAiRagProperties {
        if (chunkSize < 100 || chunkSize > 2000) {
            throw new IllegalArgumentException(
                    "导购文档分片大小必须在100到2000 Token之间"
            );
        }
        if (minChunkChars < 1 || minChunkChars > chunkSize) {
            throw new IllegalArgumentException(
                    "导购文档最小分片字符数配置不合法"
            );
        }
        if (minEmbedChars < 1 || minEmbedChars > minChunkChars) {
            throw new IllegalArgumentException(
                    "导购文档最小向量化字符数配置不合法"
            );
        }
        if (maxChunks < 1 || maxChunks > 2000) {
            throw new IllegalArgumentException(
                    "单份导购文档分片数量限制不合法"
            );
        }
        if (maxExtractedChars < 1000
                || maxExtractedChars > 2_000_000) {
            throw new IllegalArgumentException(
                    "导购文档最大提取字符数配置不合法"
            );
        }
        if (processingTimeout == null
                || processingTimeout.isNegative()
                || processingTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "导购文档处理超时时间必须为正数"
            );
        }
        if (corePoolSize < 1
                || maxPoolSize < corePoolSize
                || maxPoolSize > 8
                || queueCapacity < 1
                || queueCapacity > 500) {
            throw new IllegalArgumentException(
                    "导购文档解析线程池配置不合法"
            );
        }
    }
}
