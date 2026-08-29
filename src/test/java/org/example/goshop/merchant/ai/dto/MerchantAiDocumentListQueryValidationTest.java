package org.example.goshop.merchant.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导购文档分页查询参数校验测试。 */
class MerchantAiDocumentListQueryValidationTest {

    private static jakarta.validation.ValidatorFactory
            validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shouldApplyDefaultsAndNormalizeStatus() {
        MerchantAiDocumentListQuery defaults =
                new MerchantAiDocumentListQuery(
                        null,
                        null,
                        "   "
                );
        MerchantAiDocumentListQuery filtered =
                new MerchantAiDocumentListQuery(
                        2L,
                        10L,
                        " ready "
                );

        assertTrue(validator.validate(defaults).isEmpty());
        assertEquals(1L, defaults.effectivePage());
        assertEquals(20L, defaults.effectivePageSize());
        assertNull(defaults.normalizedStatus());

        assertTrue(validator.validate(filtered).isEmpty());
        assertEquals("READY", filtered.normalizedStatus());
    }

    @Test
    void shouldRejectInvalidPageSizeAndStatus() {
        MerchantAiDocumentListQuery invalid =
                new MerchantAiDocumentListQuery(
                        0L,
                        101L,
                        "DELETED"
                );

        assertFalse(validator.validate(invalid).isEmpty());
        assertEquals(3, validator.validate(invalid).size());
    }
}
