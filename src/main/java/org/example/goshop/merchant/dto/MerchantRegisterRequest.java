package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class MerchantRegisterRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "\\d{6}", message = "验证码格式错误")
    private String code;

    @NotBlank(message = "商户名称不能为空")
    @Size(max = 100, message = "商户名称长度不能超过100个字符")
    private String name;

    @Size(max = 1000, message = "商户简介长度不能超过1000个字符")
    private String description;

    // Logo 使用 MultipartFile/form-data 上传，再有服务端写入 OSS
    @NotNull(message = "商户Logo不能为空")
    private MultipartFile logo;
}
