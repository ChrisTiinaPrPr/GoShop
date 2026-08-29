package org.example.goshop.merchant.ai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档上传 multipart DTO 校验测试。 */
class UploadMerchantAiDocumentRequestValidationTest {

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
    void shouldRejectMissingAndEmptyFile() {
        UploadMerchantAiDocumentRequest missing =
                new UploadMerchantAiDocumentRequest(null);
        UploadMerchantAiDocumentRequest empty =
                new UploadMerchantAiDocumentRequest(
                        new MockMultipartFile(
                                "file",
                                "empty.txt",
                                "text/plain",
                                new byte[0]
                        )
                );

        assertFalse(validator.validate(missing).isEmpty());
        assertFalse(validator.validate(empty).isEmpty());
    }

    @Test
    void shouldAcceptNonEmptyFileForServiceValidation() {
        UploadMerchantAiDocumentRequest request =
                new UploadMerchantAiDocumentRequest(
                        new MockMultipartFile(
                                "file",
                                "guide.txt",
                                "text/plain",
                                new byte[]{1}
                        )
                );

        assertTrue(validator.validate(request).isEmpty());
    }
}
