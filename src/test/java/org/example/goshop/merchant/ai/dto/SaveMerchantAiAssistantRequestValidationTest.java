package org.example.goshop.merchant.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助手配置写接口的 Bean Validation 边界测试。
 */
class SaveMerchantAiAssistantRequestValidationTest {

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
    void shouldAcceptValidConfigurationAndEmptyAvatar() {
        SaveMerchantAiAssistantRequest withAvatar =
                new SaveMerchantAiAssistantRequest(
                        "星环选购顾问",
                        "https://img.test/assistant.png",
                        "您好，请告诉我您的预算。",
                        true
                );
        SaveMerchantAiAssistantRequest storeLogoFallback =
                new SaveMerchantAiAssistantRequest(
                        "店铺导购",
                        "   ",
                        "您好，需要了解什么商品？",
                        false
                );

        assertTrue(validator.validate(withAvatar).isEmpty());
        assertTrue(
                validator.validate(storeLogoFallback).isEmpty()
        );
    }

    @Test
    void shouldRejectBlankFieldsUnsafeAvatarAndMissingStatus() {
        SaveMerchantAiAssistantRequest invalid =
                new SaveMerchantAiAssistantRequest(
                        "   ",
                        "javascript:alert(1)",
                        "   ",
                        null
                );

        /* 名称、头像、欢迎语和启用状态各产生一个明确校验错误。 */
        assertEquals(4, validator.validate(invalid).size());
    }
}
