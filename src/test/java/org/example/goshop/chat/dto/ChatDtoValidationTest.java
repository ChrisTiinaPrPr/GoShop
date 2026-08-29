package org.example.goshop.chat.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDtoValidationTest {

    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shouldAcceptValidTextAndOrderPayloads() {
        SendChatMessageRequest text = new SendChatMessageRequest(
                "0ec5b9b4-2b87-4be7-9da4-a699cb8cc1ad",
                ChatMessageType.TEXT,
                "请问今天可以发货吗？",
                null
        );
        SendChatMessageRequest order = new SendChatMessageRequest(
                "eb543b0e-8222-4f96-b383-381ea9f42d77",
                ChatMessageType.ORDER,
                null,
                "2041286378101014528"
        );

        assertTrue(validator.validate(text).isEmpty());
        assertTrue(validator.validate(order).isEmpty());
    }

    @Test
    void shouldRejectMismatchedPayloadAndInvalidClientMessageId() {
        SendChatMessageRequest blankText = new SendChatMessageRequest(
                "not-a-uuid",
                ChatMessageType.TEXT,
                "   ",
                null
        );
        SendChatMessageRequest imageThroughJson = new SendChatMessageRequest(
                "0ec5b9b4-2b87-4be7-9da4-a699cb8cc1ad",
                ChatMessageType.IMAGE,
                null,
                null
        );

        assertEquals(2, validator.validate(blankText).size());
        assertFalse(validator.validate(imageThroughJson).isEmpty());
    }

    @Test
    void shouldRejectEmptyMultipartFileAndAcceptNonEmptyImage() {
        ChatImageMessageRequest empty = new ChatImageMessageRequest(
                "9cf2ec46-b301-468a-a1de-177ebf4f1a5c",
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])
        );
        ChatImageMessageRequest image = new ChatImageMessageRequest(
                "9cf2ec46-b301-468a-a1de-177ebf4f1a5c",
                new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3})
        );

        assertFalse(validator.validate(empty).isEmpty());
        assertTrue(validator.validate(image).isEmpty());
    }

    @Test
    void shouldRejectTwoCursorDirectionsAtTheSameTime() {
        ChatMessageCursorQuery query = new ChatMessageCursorQuery(100L, 200L, 30);
        Set<ConstraintViolation<ChatMessageCursorQuery>> violations = validator.validate(query);

        assertFalse(violations.isEmpty());
        assertEquals(30, new ChatMessageCursorQuery(null, null, null).effectiveLimit());
    }

    @Test
    void shouldValidateConversationAndReadIds() {
        assertFalse(validator.validate(new CreateChatConversationRequest(0L)).isEmpty());
        assertFalse(validator.validate(new MarkChatReadRequest(-1L)).isEmpty());
    }
}
