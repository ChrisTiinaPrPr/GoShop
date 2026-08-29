package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiQuestionRequest;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiStreamEvent;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiStreamEventType;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeMatchResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchRequest;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchResponse;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 买家导购回答的无答案保护、实时商品快照与引用测试。 */
@ExtendWith(MockitoExtension.class)
class BuyerMerchantAiAnswerServiceTest {

    @Mock
    private MerchantAiKnowledgeSearchService knowledgeSearchService;
    @Mock
    private ProductService productService;
    @Mock
    private MerchantAiAnswerModelClient answerModelClient;
    @Mock
    private BuyerMerchantAiRateLimitService rateLimitService;
    @InjectMocks
    private BuyerMerchantAiAnswerService answerService;

    @Test
    void shouldGenerateGroundedAnswerWithCurrentProductsAndCitations() {
        long userId = 1001L;
        long merchantId = 7001L;
        Merchant merchant = merchant(merchantId);
        MerchantAiAssistant assistant = assistant(8001L, merchantId);
        String longContent = "M7 鼠标约 59 克，提供黑色和白色。"
                + "详细参数。".repeat(60);
        List<MerchantAiKnowledgeMatchResponse> matches = List.of(
                match(9001L, 2, longContent, 0.71D),
                match(9002L, 5, "价格以实时商品接口为准。", 0.58D)
        );
        when(knowledgeSearchService.searchEnabledStoreKnowledge(
                eq(merchantId),
                any(MerchantAiKnowledgeSearchRequest.class)
        )).thenReturn(context(merchant, assistant, matches));
        PageResult<ProductListResponse> products = new PageResult<>(
                List.of(new ProductListResponse(
                        920000002L,
                        merchantId,
                        3001L,
                        "星环 M7 轻量游戏鼠标",
                        null,
                        19900L,
                        88L
                )),
                1,
                20,
                1
        );
        when(productService.listPublicMerchantProducts(
                merchantId,
                1,
                20,
                null,
                null,
                "sales"
        )).thenReturn(products);
        when(answerModelClient.stream(
                merchant,
                assistant,
                "M7鼠标多重，有哪些颜色",
                matches,
                products.records(),
                1
        )).thenReturn(Flux.just(
                "M7 约 59 克，",
                "有黑色和白色。[资料1]"
        ));

        List<BuyerMerchantAiStreamEvent> events =
                answerService.streamAnswer(
                        userId,
                        merchantId,
                        new BuyerMerchantAiQuestionRequest(
                                "  M7鼠标多重，有哪些颜色  "
                        )
                ).collectList().block();

        assertEquals(4, events.size());
        assertEquals(BuyerMerchantAiStreamEventType.STARTED, events.get(0).type());
        assertEquals("https://img/store.png", events.get(0).assistantAvatarUrl());
        assertEquals(BuyerMerchantAiStreamEventType.TEXT_DELTA, events.get(1).type());
        assertEquals("M7 约 59 克，", events.get(1).delta());
        assertEquals(BuyerMerchantAiStreamEventType.TEXT_DELTA, events.get(2).type());
        assertEquals(BuyerMerchantAiStreamEventType.COMPLETED, events.get(3).type());
        assertTrue(events.get(3).grounded());
        assertEquals(2, events.get(3).citations().size());
        assertEquals(1, events.get(3).citations().get(0).citationNo());
        assertTrue(events.get(3).citations().get(0).excerpt().length() <= 261);

        ArgumentCaptor<MerchantAiKnowledgeSearchRequest> searchRequest =
                ArgumentCaptor.forClass(
                        MerchantAiKnowledgeSearchRequest.class
                );
        verify(knowledgeSearchService).searchEnabledStoreKnowledge(
                eq(merchantId),
                searchRequest.capture()
        );
        assertEquals(4, searchRequest.getValue().effectiveTopK());
        assertEquals(
                0.50D,
                searchRequest.getValue().effectiveSimilarityThreshold()
        );
        verify(rateLimitService).checkAllowed(userId, merchantId);
    }

    @Test
    void shouldReturnDeterministicFallbackWithoutModelOrProductQuery() {
        long merchantId = 7002L;
        Merchant merchant = merchant(merchantId);
        MerchantAiAssistant assistant = assistant(8002L, merchantId);
        when(knowledgeSearchService.searchEnabledStoreKnowledge(
                eq(merchantId),
                any(MerchantAiKnowledgeSearchRequest.class)
        )).thenReturn(context(merchant, assistant, List.of()));

        List<BuyerMerchantAiStreamEvent> events =
                answerService.streamAnswer(
                        1002L,
                        merchantId,
                        new BuyerMerchantAiQuestionRequest("退款流程是什么")
                ).collectList().block();

        assertEquals(3, events.size());
        assertEquals(BuyerMerchantAiStreamEventType.STARTED, events.get(0).type());
        assertTrue(events.get(1).delta().contains("暂时无法确认"));
        assertEquals(BuyerMerchantAiStreamEventType.COMPLETED, events.get(2).type());
        assertFalse(events.get(2).grounded());
        assertTrue(events.get(2).citations().isEmpty());
        verify(productService, never()).listPublicMerchantProducts(
                any(), any(Long.class), any(Long.class),
                any(), any(), any()
        );
        verify(answerModelClient, never()).stream(
                any(), any(), any(), any(), any(), any(Long.class)
        );
    }

    @Test
    void shouldRejectMissingBuyerIdentityBeforeExternalWork() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> answerService.streamAnswer(
                        null,
                        7003L,
                        new BuyerMerchantAiQuestionRequest("推荐键盘")
                )
        );

        assertEquals(40101, exception.getCode());
        verify(knowledgeSearchService, never())
                .searchEnabledStoreKnowledge(any(), any());
        verify(rateLimitService, never()).checkAllowed(any(), any());
    }

    @Test
    void shouldEmitErrorInsteadOfCompletingWhenModelStreamFails() {
        long merchantId = 7004L;
        Merchant merchant = merchant(merchantId);
        MerchantAiAssistant assistant = assistant(8004L, merchantId);
        List<MerchantAiKnowledgeMatchResponse> matches = List.of(
                match(9004L, 1, "键盘支持三种连接模式。", 0.72D)
        );
        when(knowledgeSearchService.searchEnabledStoreKnowledge(
                eq(merchantId),
                any(MerchantAiKnowledgeSearchRequest.class)
        )).thenReturn(context(merchant, assistant, matches));
        PageResult<ProductListResponse> products = new PageResult<>(
                List.of(), 1, 20, 0
        );
        when(productService.listPublicMerchantProducts(
                merchantId, 1, 20, null, null, "sales"
        )).thenReturn(products);
        when(answerModelClient.stream(
                merchant,
                assistant,
                "键盘支持什么连接方式",
                matches,
                products.records(),
                0
        )).thenReturn(Flux.concat(
                Flux.just("支持蓝牙、"),
                Flux.error(new BusinessException(50301, "模型流中断"))
        ));

        List<BuyerMerchantAiStreamEvent> events =
                answerService.streamAnswer(
                        1004L,
                        merchantId,
                        new BuyerMerchantAiQuestionRequest(
                                "键盘支持什么连接方式"
                        )
                ).collectList().block();

        assertEquals(3, events.size());
        assertEquals(BuyerMerchantAiStreamEventType.STARTED, events.get(0).type());
        assertEquals("支持蓝牙、", events.get(1).delta());
        assertEquals(BuyerMerchantAiStreamEventType.ERROR, events.get(2).type());
        assertEquals(50301, events.get(2).code());
    }

    private Merchant merchant(long merchantId) {
        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setName("星环外设馆");
        merchant.setLogoUrl("https://img/store.png");
        merchant.setStatus(1);
        return merchant;
    }

    private MerchantAiAssistant assistant(long id, long merchantId) {
        MerchantAiAssistant assistant = new MerchantAiAssistant();
        assistant.setId(id);
        assistant.setMerchantId(merchantId);
        assistant.setName("星环导购");
        assistant.setAvatarUrl(null);
        assistant.setEnabled(1);
        return assistant;
    }

    private MerchantAiKnowledgeMatchResponse match(
            long documentId,
            int chunkIndex,
            String content,
            double score
    ) {
        return new MerchantAiKnowledgeMatchResponse(
                documentId,
                "guide.md",
                chunkIndex,
                content,
                score
        );
    }

    private MerchantAiKnowledgeSearchService.EnabledStoreKnowledge context(
            Merchant merchant,
            MerchantAiAssistant assistant,
            List<MerchantAiKnowledgeMatchResponse> matches
    ) {
        return new MerchantAiKnowledgeSearchService.EnabledStoreKnowledge(
                merchant,
                assistant,
                new MerchantAiKnowledgeSearchResponse(
                        "query",
                        4,
                        0.50D,
                        matches.size(),
                        matches
                )
        );
    }
}
