package org.example.goshop.merchant.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商家知识库检索请求边界测试。 */
class MerchantAiKnowledgeSearchRequestValidationTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void shouldApplySafeDefaultsAndNormalizeQuery() {
        MerchantAiKnowledgeSearchRequest request =
                new MerchantAiKnowledgeSearchRequest(
                        "  适合办公的键盘  ",
                        null,
                        null
                );

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("适合办公的键盘", request.normalizedQuery());
        assertEquals(5, request.effectiveTopK());
        assertEquals(0.50D, request.effectiveSimilarityThreshold());
    }

    @Test
    void shouldRejectBlankAndOversizedQuery() {
        assertFalse(validator.validate(
                new MerchantAiKnowledgeSearchRequest(
                        "   ",
                        null,
                        null
                )
        ).isEmpty());
        assertFalse(validator.validate(
                new MerchantAiKnowledgeSearchRequest(
                        "问".repeat(501),
                        null,
                        null
                )
        ).isEmpty());
    }

    @Test
    void shouldRejectUnsafeSearchLimits() {
        assertFalse(validator.validate(
                new MerchantAiKnowledgeSearchRequest(
                        "键盘",
                        11,
                        1.01D
                )
        ).isEmpty());
        assertFalse(validator.validate(
                new MerchantAiKnowledgeSearchRequest(
                        "键盘",
                        0,
                        0.49D
                )
        ).isEmpty());
    }
}
