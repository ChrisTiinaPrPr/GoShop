package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.Size;

public record ReviewRefundRequest(
        @Size(max = 255, message = "审核意见长度不能超过255个字符")
        String reviewRemark
) {
}
