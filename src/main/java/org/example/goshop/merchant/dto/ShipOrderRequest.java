package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipOrderRequest(
        @NotBlank(message = "物流公司不能为空")
        @Size(max = 100, message = "物流公司长度不能超过100个字符")
        String shippingCompany,

        @NotBlank(message = "运单号不能为空")
        @Size(max = 100, message = "运单号长度不能超过100个字符")
        String trackingNo
) {
}
