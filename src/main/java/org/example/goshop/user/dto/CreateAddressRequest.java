package org.example.goshop.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAddressRequest(

        @NotBlank(message = "收货人不能为空")
        @Size(max = 50, message = "收货人长度不能超过50个字符")
        String receiver,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "省份不能为空")
        @Size(max = 50, message = "省份长度不能超过50个字符")
        String province,

        @NotBlank(message = "城市不能为空")
        @Size(max = 50, message = "城市长度不能超过50个字符")
        String city,

        @NotBlank(message = "区县不能为空")
        @Size(max = 50, message = "区县长度不能超过50个字符")
        String district,

        @NotBlank(message = "详细地址不能为空")
        @Size(max = 255, message = "详细地址长度不能超过255个字符")
        String detail,

        // 不传时默认不是默认地址；用户第一条地址会自动成为默认地址
        Boolean isDefault
) {
}
