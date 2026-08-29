package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMerchantCategoryRequest(

        // 不传时不修改；传空白字符串会校验失败
        @Size(max = 50,message = "分类名称不能超过50个字符")
        @Pattern(regexp = ".*\\S.*", message = "分类名称不能为空")
        String name,

        // 不传时不修改； 0 表示u移动为一级分类；正数表示新的父分类 ID
        @Min(value = 0, message = "父分类 ID 不能小于 0")
        Long parentId,

        // 不传时不修改
        @Min(value = 0, message = "排序值不能小于 0")
        Integer sort,

        // 不传时不修改；0-禁用，1-启用
        @Min(value = 0, message = "只能传0或1")
        @Max(value = 1, message = "只能传0或1")
        Integer status
) {
}
