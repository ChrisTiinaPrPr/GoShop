package org.example.goshop.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "goshop.oss")
public record OssProperties(
        String endpoint,
        String accessKeyId,
        String accessKeySecret,
        String bucketName
) {
}
