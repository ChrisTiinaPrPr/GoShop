package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 买家在指定店铺内向智能导购提交的问题。 */
@Schema(
        name = "BuyerMerchantAiQuestionRequest",
        description = "买家店铺内智能导购提问请求"
)
public record BuyerMerchantAiQuestionRequest(
        @NotBlank(message = "导购问题不能为空")
        @Size(max = 500, message = "导购问题不能超过500个字符")
        String question
) {

    /** 统一去除首尾空白，使检索和模型看到同一份问题文本。 */
    public String normalizedQuestion() {
        return question == null ? null : question.strip();
    }
}
