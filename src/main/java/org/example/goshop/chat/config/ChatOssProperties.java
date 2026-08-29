package org.example.goshop.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 聊天私有 OSS 配置。
 *
 * @param bucketName       聊天图片专用私有 Bucket
 * @param signedUrlMinutes 图片签名 URL 有效分钟数
 */
@ConfigurationProperties(prefix = "goshop.chat.oss")
public record ChatOssProperties(
        String bucketName,
        int signedUrlMinutes
) {

    public ChatOssProperties {
        if (!StringUtils.hasText(bucketName)) {
            throw new IllegalArgumentException(
                    "聊天图片私有 OSS Bucket 不能为空"
            );
        }
        if (signedUrlMinutes < 1 || signedUrlMinutes > 60) {
            throw new IllegalArgumentException(
                    "聊天图片签名 URL 有效分钟数必须在 1 到 60 分钟之间"
            );
        }
    }
}
