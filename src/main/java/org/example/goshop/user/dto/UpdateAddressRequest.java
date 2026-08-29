package org.example.goshop.user.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 *  地址表字段均为 NOT NULL，因此编辑时要求提交完整地址信息。
 *  isDefault 不在此接口修改，由“设为默认地址”接口单独处理。
 */
public record UpdateAddressRequest(
        @NotBlank(message = "收货人不能为空")
        @Size(max = 50, message = "收货人长度不能超过50个字符")
        String receiver,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "省不能为空")
        @Size(max = 50, message = "省长度不能超过50个字符")
        String province,

        @NotBlank(message = "城市不能为空")
        @Size(max = 50, message = "城市长度不能超过50个字符")
        String city,

        @NotBlank(message = "区县不能为空")
        @Size(max = 50, message = "区县长度不能超过50个字符")
        String district,

        @NotBlank(message = "详细地址不能为空")
        @Size(max = 200, message = "详细地址长度不能超过200个字符")
        String detail
) {
}
