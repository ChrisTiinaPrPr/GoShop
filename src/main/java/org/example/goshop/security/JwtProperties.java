package org.example.goshop.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;


/**
 * JWT 签名参数。
 *
 * <p>签名密钥只能由本地 {@code .env}、部署环境变量或密钥管理服务注入。
 * 配置绑定阶段即校验长度，避免空值或弱密钥在应用启动后才暴露问题。</p>
 */
@Validated
@ConfigurationProperties(prefix = "goshop.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT_SECRET 不能为空")
        @Size(min = 32, message = "JWT_SECRET 至少需要 32 个字符")
        String secret,
        @Positive(message = "JWT_ACCESS_TOKEN_MINUTES 必须为正数")
        long accessTokenMinutes
) {
}
