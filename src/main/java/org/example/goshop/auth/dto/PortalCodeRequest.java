package org.example.goshop.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 门户验证码请求。验证码场景由访问的买家/商家端点决定，不接受客户端自定义。
 */
public record PortalCodeRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
        String phone
) {
}
