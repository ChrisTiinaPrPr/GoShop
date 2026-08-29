package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchRequest;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchResponse;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.entity.MerchantAiKnowledgeChunkRow;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentChunkMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.service.MerchantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家知识库的租户过滤、数据库复核与故障收口测试。 */
@ExtendWith(MockitoExtension.class)
class MerchantAiKnowledgeSearchServiceTest {

    @Mock
    private MerchantService merchantService;
    @Mock
    private MerchantAiAssistantMapper assistantMapper;
    @Mock
    private MerchantAiDocumentChunkMapper chunkMapper;
    @Mock
    private MerchantAiRagAvailabilityService availabilityService;
    @Mock
    private VectorStore vectorStore;
    @Spy
    private MerchantAiKnowledgeReranker knowledgeReranker =
            new MerchantAiKnowledgeReranker();

    @InjectMocks
    private MerchantAiKnowledgeSearchService searchService;

    @Test
    void shouldFilterByTenantAndReturnOnlyReadyMysqlContent() {
        long userId = 1001L;
        long merchantId = 7001L;
        long assistantId = 8001L;
        MerchantAiAssistant assistant = assistant(assistantId, merchantId);
        Document first = candidate("vector-1", "Qdrant旧正文", 0.91D);
        Document stale = candidate("vector-stale", "已删除正文", 0.88D);
        Document second = candidate("vector-2", "Qdrant载荷", 0.82D);

        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(assistant);
        when(availabilityService.requireVectorStore())
                .thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(first, stale, second));
        when(chunkMapper.selectReadyOwnedChunks(
                eq(merchantId),
                eq(assistantId),
                eq(List.of("vector-1", "vector-stale", "vector-2"))
        )).thenReturn(List.of(
                chunk("vector-1", 9001L, 0, "MySQL中的K87正文"),
                chunk("vector-2", 9002L, 3, "MySQL中的M7正文")
        ));

        MerchantAiKnowledgeSearchResponse response =
                searchService.searchCurrentKnowledge(
                        userId,
                        new MerchantAiKnowledgeSearchRequest(
                                "  适合办公的键盘  ",
                                2,
                                0.4D
                        )
                );

        assertEquals("适合办公的键盘", response.query());
        assertEquals(2, response.matchCount());
        assertEquals("MySQL中的K87正文", response.matches().get(0).content());
        assertEquals(0.91D, response.matches().get(0).score());
        assertEquals("MySQL中的M7正文", response.matches().get(1).content());

        ArgumentCaptor<SearchRequest> searchCaptor =
                ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(searchCaptor.capture());
        SearchRequest actualSearch = searchCaptor.getValue();
        assertEquals(12, actualSearch.getTopK());
        assertEquals(0D, actualSearch.getSimilarityThreshold());
        assertTrue(actualSearch.getQuery().startsWith("Instruct:"));
        assertTrue(actualSearch.getQuery().endsWith("适合办公的键盘"));
        assertTrue(actualSearch.hasFilterExpression());
        assertTrue(actualSearch.getFilterExpression()
                .toString()
                .contains("merchant_id"));
        assertTrue(actualSearch.getFilterExpression()
                .toString()
                .contains("assistant_id"));
    }

    @Test
    void shouldReturnEmptyWithoutQueryingMysqlWhenQdrantHasNoMatch() {
        long userId = 1002L;
        long merchantId = 7002L;
        MerchantAiAssistant assistant = assistant(8002L, merchantId);
        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(assistant);
        when(availabilityService.requireVectorStore())
                .thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        MerchantAiKnowledgeSearchResponse response =
                searchService.searchCurrentKnowledge(
                        userId,
                        new MerchantAiKnowledgeSearchRequest(
                                "不存在的商品",
                                null,
                                null
                        )
                );

        assertEquals(0, response.matchCount());
        assertTrue(response.matches().isEmpty());
        verify(chunkMapper, never()).selectReadyOwnedChunks(
                any(), any(), any()
        );
    }

    @Test
    void shouldRecallBroadProductQuestionBeforeLocalReranking() {
        long userId = 1007L;
        long merchantId = 7007L;
        long assistantId = 8007L;
        MerchantAiAssistant assistant = assistant(assistantId, merchantId);
        Document generic = candidate("generic", "通用说明", 0.45D);
        Document mouse = candidate("mouse", "M7 商品", 0.38D);

        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(assistant);
        when(availabilityService.requireVectorStore())
                .thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(generic, mouse));
        when(chunkMapper.selectReadyOwnedChunks(
                merchantId,
                assistantId,
                List.of("generic", "mouse")
        )).thenReturn(List.of(
                chunk(
                        "generic",
                        9003L,
                        0,
                        "重要说明：本店销售键盘、鼠标和游戏耳机。"
                ),
                chunk(
                        "mouse",
                        9003L,
                        2,
                        "星环 M7 轻量游戏鼠标适合快速移动操作。"
                )
        ));

        MerchantAiKnowledgeSearchResponse response =
                searchService.searchCurrentKnowledge(
                        userId,
                        new MerchantAiKnowledgeSearchRequest(
                                "有没有适合打FPS的游戏鼠标？",
                                1,
                                null
                        )
                );

        assertEquals(1, response.matchCount());
        assertEquals(2, response.matches().get(0).chunkIndex());
        assertEquals(0.38D, response.matches().get(0).score());

        ArgumentCaptor<SearchRequest> searchCaptor =
                ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(searchCaptor.capture());
        assertEquals(12, searchCaptor.getValue().getTopK());
        assertEquals(0D, searchCaptor.getValue()
                .getSimilarityThreshold());
    }

    @Test
    void shouldRejectMerchantWithoutAssistantBeforeCallingVectorStore() {
        long userId = 1003L;
        long merchantId = 7003L;
        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> searchService.searchCurrentKnowledge(
                        userId,
                        new MerchantAiKnowledgeSearchRequest(
                                "键盘",
                                null,
                                null
                        )
                )
        );

        assertEquals(40901, exception.getCode());
        verify(availabilityService, never()).requireVectorStore();
    }

    @Test
    void shouldConvertVectorFailureToRetryableBusinessError() {
        long userId = 1004L;
        long merchantId = 7004L;
        MerchantAiAssistant assistant = assistant(8004L, merchantId);
        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(assistant);
        when(availabilityService.requireVectorStore())
                .thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("remote details"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> searchService.searchCurrentKnowledge(
                        userId,
                        new MerchantAiKnowledgeSearchRequest(
                                "键盘",
                                null,
                                null
                        )
                )
        );

        assertEquals(50301, exception.getCode());
        assertEquals(
                "智能导购知识库检索暂时不可用，请稍后重试",
                exception.getMessage()
        );
    }

    @Test
    void shouldSearchOnlyEnabledAssistantForBuyerStoreContext() {
        long merchantId = 7005L;
        Merchant store = enabledMerchant(merchantId);
        MerchantAiAssistant assistant = assistant(8005L, merchantId);
        assistant.setEnabled(1);
        when(merchantService.requireEnabledMerchant(merchantId))
                .thenReturn(store);
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(assistant);
        when(availabilityService.requireVectorStore())
                .thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        MerchantAiKnowledgeSearchService.EnabledStoreKnowledge result =
                searchService.searchEnabledStoreKnowledge(
                        merchantId,
                        new MerchantAiKnowledgeSearchRequest(
                                "M7鼠标",
                                4,
                                null
                        )
                );

        assertEquals(store, result.merchant());
        assertEquals(assistant, result.assistant());
        assertTrue(result.knowledge().matches().isEmpty());
    }

    @Test
    void shouldHideMissingAndDisabledAssistantFromBuyer() {
        long merchantId = 7006L;
        MerchantAiAssistant disabled = assistant(8006L, merchantId);
        disabled.setEnabled(0);
        when(merchantService.requireEnabledMerchant(merchantId))
                .thenReturn(enabledMerchant(merchantId));
        when(assistantMapper.selectByMerchantId(merchantId))
                .thenReturn(disabled);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> searchService.searchEnabledStoreKnowledge(
                        merchantId,
                        new MerchantAiKnowledgeSearchRequest(
                                "键盘",
                                null,
                                null
                        )
                )
        );

        assertEquals(40401, exception.getCode());
        assertEquals("该店铺暂未启用智能导购", exception.getMessage());
        verify(availabilityService, never()).requireVectorStore();
    }

    private MerchantAiAssistant assistant(Long id, Long merchantId) {
        MerchantAiAssistant assistant = new MerchantAiAssistant();
        assistant.setId(id);
        assistant.setMerchantId(merchantId);
        return assistant;
    }

    private MerchantProfileResponse merchant(Long id) {
        return new MerchantProfileResponse(
                id,
                "测试店铺",
                null,
                null
        );
    }

    private Merchant enabledMerchant(Long id) {
        Merchant merchant = new Merchant();
        merchant.setId(id);
        merchant.setName("测试店铺");
        merchant.setStatus(1);
        return merchant;
    }

    private Document candidate(
            String vectorId,
            String vectorContent,
            Double score
    ) {
        return Document.builder()
                .id(vectorId)
                .text(vectorContent)
                .metadata(Map.of())
                .score(score)
                .build();
    }

    private MerchantAiKnowledgeChunkRow chunk(
            String vectorId,
            Long documentId,
            int chunkIndex,
            String content
    ) {
        MerchantAiKnowledgeChunkRow chunk =
                new MerchantAiKnowledgeChunkRow();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setOriginalFilename("guide.md");
        return chunk;
    }
}
