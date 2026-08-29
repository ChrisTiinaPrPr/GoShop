package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateMerchantCategoryRequest(

        @NotBlank(message = "分类名称不能为空")
        @Size(max = 50, message = "分类名称长度不能超过50")
        String name,

        // 不传表示一级分类；传入时必须是当前商家自己的分类 ID
        @Positive(message = "父级分类 ID 必须为正数")
        Long parentId,

        // 不传时默认为 0，数值越小排序越靠前
        Integer sort
) {
}
