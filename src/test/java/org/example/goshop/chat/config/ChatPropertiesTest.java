package org.example.goshop.chat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatPropertiesTest {

    @Test
    void shouldNormalizeOriginsAndConvertImageLimitToBytes() {
        ChatProperties properties = new ChatProperties(
                List.of(
                        " http://localhost:5173 ",
                        "http://localhost:5174",
                        "http://localhost:5173"
                ),
                5,
                30
        );

        assertEquals(2, properties.allowedOrigins().size());
        assertArrayEquals(
                new String[]{"http://localhost:5173", "http://localhost:5174"},
                properties.allowedOriginsArray()
        );
        assertEquals(5L * 1024L * 1024L, properties.imageMaxSizeBytes());
        assertEquals(30, properties.messageRatePerMinute());
    }

    @Test
    void shouldRejectEmptyOrWildcardOriginWhitelist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(List.of(), 5, 30)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(List.of("*"), 5, 30)
        );
    }

    @Test
    void shouldRejectUnsafeImageAndMessageLimits() {
        List<String> origins = List.of("http://localhost:5173");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(origins, 0, 30)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(origins, 51, 30)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(origins, 5, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatProperties(origins, 5, 1001)
        );
    }
}
