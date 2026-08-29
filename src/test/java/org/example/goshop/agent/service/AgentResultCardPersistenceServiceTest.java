package org.example.goshop.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.goshop.agent.dto.AgentResultCardData;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.example.goshop.agent.tool.product.AgentProductSearchItem;
import org.example.goshop.agent.tool.product.AgentProductSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 结果卡片落库、恢复和损坏降级测试。
 */
class AgentResultCardPersistenceServiceTest {

    private AgentMessageMapper messageMapper;
    private ObjectMapper objectMapper;
    private AgentResultCardPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AgentMessageMapper.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        persistenceService = new AgentResultCardPersistenceService(
                messageMapper,
                objectMapper
        );
    }

    @Test
    void shouldBindToolCallIdAndPersistValidJson() throws Exception {
        when(messageMapper.appendResultCard(eq(9001L), anyString()))
                .thenReturn(1);

        AgentResultCardData stored = persistenceService.append(
                9001L,
                "tool-call-1",
                productCard()
        );

        assertEquals("tool-call-1", stored.toolCallId());

        ArgumentCaptor<String> jsonCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(messageMapper).appendResultCard(
                eq(9001L),
                jsonCaptor.capture()
        );

        AgentResultCardData decoded = objectMapper.readValue(
                jsonCaptor.getValue(),
                AgentResultCardData.class
        );
        assertEquals(stored, decoded);
    }

    @Test
    void shouldRestoreCardsFromAssistantMessageJson() throws Exception {
        AgentResultCardData stored =
                productCard().bindToolCallId("tool-call-1");
        AgentMessage message = new AgentMessage();
        message.setResultCardsJson(
                objectMapper.writeValueAsString(List.of(stored))
        );

        List<AgentResultCardData> restored =
                persistenceService.read(message);

        assertEquals(List.of(stored), restored);
        assertEquals(
                List.of(stored),
                persistenceService.toResponse(message).resultCards()
        );
    }

    @Test
    void shouldDegradeToTextHistoryWhenStoredJsonIsCorrupted() {
        AgentMessage message = new AgentMessage();
        message.setResultCardsJson("{not-valid-json");

        assertTrue(persistenceService.read(message).isEmpty());
    }

    @Test
    void shouldRejectGhostCardWhenRunIsNoLongerActive() {
        when(messageMapper.appendResultCard(eq(9001L), anyString()))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.append(
                        9001L,
                        "tool-call-1",
                        productCard()
                )
        );
    }

    private AgentResultCardData productCard() {
        return AgentResultCardData.fromProductSearch(
                new AgentProductSearchResult(
                        List.of(new AgentProductSearchItem(
                                101L,
                                8L,
                                "无线耳机",
                                "https://image.example/101.jpg",
                                19900L,
                                35L
                        )),
                        1,
                        false
                )
        );
    }
}
