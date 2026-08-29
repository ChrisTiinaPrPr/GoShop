package org.example.goshop.merchant.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 买家店铺导购问题的输入边界测试。 */
class BuyerMerchantAiQuestionRequestValidationTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void shouldNormalizeValidQuestion() {
        BuyerMerchantAiQuestionRequest request =
                new BuyerMerchantAiQuestionRequest(
                        "  M7 鼠标有哪些颜色？  "
                );

        assertTrue(validator.validate(request).isEmpty());
        assertEquals(
                "M7 鼠标有哪些颜色？",
                request.normalizedQuestion()
        );
    }

    @Test
    void shouldRejectBlankAndOversizedQuestion() {
        assertFalse(validator.validate(
                new BuyerMerchantAiQuestionRequest("   ")
        ).isEmpty());
        assertFalse(validator.validate(
                new BuyerMerchantAiQuestionRequest("问".repeat(501))
        ).isEmpty());
    }
}
