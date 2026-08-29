package org.example.goshop.merchant.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 商家智能导购文档上传限制与私有 OSS 配置。
 *
 * @param maxSizeMb               单个文件最大 MB，不能超过全局 multipart 限制
 * @param maxDocumentsPerAssistant 单个助手最多保留的文档数
 * @param bucketName              导购文档私有 OSS Bucket
 */
@ConfigurationProperties(prefix = "goshop.merchant-ai.documents")
public record MerchantAiDocumentProperties(
        int maxSizeMb,
        int maxDocumentsPerAssistant,
        String bucketName
) {

    public MerchantAiDocumentProperties {
        if (maxSizeMb < 1 || maxSizeMb > 5) {
            throw new IllegalArgumentException(
                    "导购文档大小限制必须在1到5MB之间"
            );
        }
        if (maxDocumentsPerAssistant < 1
                || maxDocumentsPerAssistant > 100) {
            throw new IllegalArgumentException(
                    "单个助手文档数量限制必须在1到100之间"
            );
        }
        if (!StringUtils.hasText(bucketName)) {
            throw new IllegalArgumentException(
                    "导购文档私有 OSS Bucket 不能为空"
            );
        }
    }

    /** 转换成真实字节上限，避免各调用方重复计算。 */
    public long maxSizeBytes() {
        return maxSizeMb * 1024L * 1024L;
    }
}
