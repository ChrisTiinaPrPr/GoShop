package org.example.goshop.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.agent.entity.AgentToolCall;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.agent.service.model.AgentAddCartActionProposal;
import org.example.goshop.agent.tool.AgentToolEventChannel;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.agent.tool.cart.ProposeAddCartItemTool;
import org.example.goshop.agent.tool.product.AgentProductDetailResult;
import org.example.goshop.agent.tool.product.AgentProductQueryService;
import org.example.goshop.agent.tool.product.AgentProductSearchItem;
import org.example.goshop.agent.tool.product.AgentProductSearchResult;
import org.example.goshop.agent.tool.product.AgentProductSkuDetail;
import org.example.goshop.agent.tool.product.ProductDetailTool;
import org.example.goshop.agent.tool.product.ProductSearchTool;
import org.example.goshop.product.dto.ProductSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 明确加购请求确定性工具链的回归测试。
 */
class AgentAddCartDeterministicOrchestratorTest {

    private AgentToolCallMapper toolCallMapper;
    private AgentProductQueryService productQueryService;
    private ProductSearchTool productSearchTool;
    private ProductDetailTool productDetailTool;
    private ProposeAddCartItemTool proposeAddCartItemTool;
    private AgentAddCartDeterministicOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        toolCallMapper = mock(AgentToolCallMapper.class);
        productQueryService = mock(
                AgentProductQueryService.class
        );
        productSearchTool = mock(ProductSearchTool.class);
        productDetailTool = mock(ProductDetailTool.class);
        proposeAddCartItemTool = mock(
                ProposeAddCartItemTool.class
        );

        orchestrator =
                new AgentAddCartDeterministicOrchestrator(
                        toolCallMapper,
                        new ObjectMapper(),
                        productQueryService,
                        productSearchTool,
                        productDetailTool,
                        proposeAddCartItemTool
                );
    }

    @Test
    void shouldExecuteFullToolChainForContextualPurpleSkuSelection() {
        long conversationId = 2001L;
        long runId = 3001L;
        long previousKeyboardProductId = 4000L;
        long productId = 4001L;
        long purpleSkuId = 930000006L;

        AgentToolCall previousDetailCall =
                new AgentToolCall();
        previousDetailCall.setResultSummaryJson(
                "{\"productId\":4000}"
        );

        when(toolCallMapper
                .selectLatestSuccessfulBeforeRun(
                        conversationId,
                        runId,
                        "get_product_detail"
                ))
                .thenReturn(previousDetailCall);

        AgentProductDetailResult previousDetail =
                detail(
                        previousKeyboardProductId,
                        List.of()
                );

        when(productQueryService.getDetail(
                previousKeyboardProductId
        ))
                .thenReturn(previousDetail);

        AgentProductSearchResult searchResult =
                new AgentProductSearchResult(
                        List.of(
                                new AgentProductSearchItem(
                                        productId,
                                        11L,
                                        "星环 H1 无线游戏耳机",
                                        null,
                                        1L,
                                        0L
                                )
                        ),
                        1,
                        false
                );

        when(productSearchTool.searchProducts(
                eq("游戏耳机"),
                isNull(),
                isNull(),
                isNull(),
                eq(ProductSort.LATEST),
                eq(10),
                any(ToolContext.class)
        )).thenReturn(searchResult);

        AgentProductSkuDetail blackSku =
                new AgentProductSkuDetail(
                        930000005L,
                        Map.of("颜色", "深空黑"),
                        1L,
                        10
                );
        AgentProductSkuDetail purpleSku =
                new AgentProductSkuDetail(
                        purpleSkuId,
                        Map.of(
                                "颜色",
                                "星云紫",
                                "麦克风",
                                "可拆卸"
                        ),
                        1L,
                        48
                );

        when(productDetailTool.getProductDetail(
                eq(productId),
                any(ToolContext.class)
        )).thenReturn(
                detail(
                        productId,
                        List.of(blackSku, purpleSku)
                )
        );

        AgentAddCartActionProposal proposal =
                new AgentAddCartActionProposal(
                        6001L,
                        "ADD_CART_ITEM",
                        "PENDING",
                        productId,
                        purpleSkuId,
                        1,
                        "星环 H1 无线游戏耳机",
                        purpleSku.specifications(),
                        1L,
                        null,
                        Instant.now().plusSeconds(600)
                );

        when(proposeAddCartItemTool
                .proposeAddCartItem(
                        eq(productId),
                        eq(purpleSkuId),
                        eq(1),
                        any(ToolContext.class)
                ))
                .thenReturn(proposal);

        AgentToolRequestContext requestContext =
                new AgentToolRequestContext(
                        1001L,
                        conversationId,
                        runId,
                        new AgentToolEventChannel(
                                conversationId,
                                runId
                        )
                );

        List<Message> messages = List.of(
                new UserMessage("再给我推荐一款游戏耳机"),
                new AssistantMessage(
                        "推荐星环 H1 无线游戏耳机，有深空黑和星云紫两个规格。"
                ),
                new UserMessage("我要紫色的")
        );

        String response = orchestrator.execute(
                "我要紫色的",
                messages,
                requestContext
        );

        assertTrue(response.contains("已生成加购待确认卡片"));

        InOrder order = inOrder(
                productSearchTool,
                productDetailTool,
                proposeAddCartItemTool
        );

        order.verify(productSearchTool)
                .searchProducts(
                        eq("游戏耳机"),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(ProductSort.LATEST),
                        eq(10),
                        any(ToolContext.class)
                );
        order.verify(productDetailTool)
                .getProductDetail(
                        eq(productId),
                        any(ToolContext.class)
                );
        order.verify(proposeAddCartItemTool)
                .proposeAddCartItem(
                        eq(productId),
                        eq(purpleSkuId),
                        eq(1),
                        any(ToolContext.class)
                );

        verify(toolCallMapper)
                .selectLatestSuccessfulBeforeRun(
                        conversationId,
                        runId,
                        "get_product_detail"
                );

        /*
         * 同一商品再覆盖截图中的裸规格回复“黑色款”。上一条助手已经
         * 询问需要加入哪一款，因此本轮必须从实时详情中命中深空黑 SKU。
         */
        when(proposeAddCartItemTool
                .proposeAddCartItem(
                        eq(productId),
                        eq(930000005L),
                        eq(1),
                        any(ToolContext.class)
                ))
                .thenReturn(new AgentAddCartActionProposal(
                        6002L,
                        "ADD_CART_ITEM",
                        "PENDING",
                        productId,
                        930000005L,
                        1,
                        "星环 H1 无线游戏耳机",
                        blackSku.specifications(),
                        1L,
                        null,
                        Instant.now().plusSeconds(600)
                ));

        List<Message> bareBlackMessages = List.of(
                new UserMessage("再给我推荐一款游戏耳机"),
                new AssistantMessage(
                        "深空黑和星云紫价格一样，需要帮您把哪一款加入购物车呢？"
                ),
                new UserMessage("黑色款")
        );

        String blackResponse = orchestrator.execute(
                "黑色款",
                bareBlackMessages,
                requestContext
        );

        assertTrue(blackResponse.contains("已生成加购待确认卡片"));
        verify(proposeAddCartItemTool)
                .proposeAddCartItem(
                        eq(productId),
                        eq(930000005L),
                        eq(1),
                        any(ToolContext.class)
                );
    }

    @Test
    void shouldReuseVerifiedTitleForFirstAddCartRequest() {
        long conversationId = 2101L;
        long runId = 3101L;
        long keyboardProductId = 4101L;
        long keyboardSkuId = 5101L;

        AgentToolCall previousDetailCall = new AgentToolCall();
        previousDetailCall.setResultSummaryJson(
                "{\"productId\":4101}"
        );
        when(toolCallMapper.selectLatestSuccessfulBeforeRun(
                conversationId,
                runId,
                "get_product_detail"
        )).thenReturn(previousDetailCall);

        AgentProductSkuDetail blackRedSku =
                new AgentProductSkuDetail(
                        keyboardSkuId,
                        Map.of(
                                "轴体",
                                "线性红轴",
                                "颜色",
                                "深空黑"
                        ),
                        29900L,
                        80
                );

        AgentProductSkuDetail whiteRedSku =
                new AgentProductSkuDetail(
                        5102L,
                        Map.of(
                                "轴体",
                                "线性红轴",
                                "颜色",
                                "月光白"
                        ),
                        29900L,
                        60
                );

        AgentProductDetailResult keyboardDetail =
                new AgentProductDetailResult(
                        keyboardProductId,
                        11L,
                        "星环 K87 三模机械键盘",
                        "机械键盘",
                        null,
                        0L,
                        List.of(blackRedSku, whiteRedSku),
                        false
                );
        when(productQueryService.getDetail(keyboardProductId))
                .thenReturn(keyboardDetail);
        when(productSearchTool.searchProducts(
                any(String.class),
                isNull(),
                isNull(),
                isNull(),
                eq(ProductSort.LATEST),
                eq(10),
                any(ToolContext.class)
        )).thenReturn(new AgentProductSearchResult(
                List.of(new AgentProductSearchItem(
                        keyboardProductId,
                        11L,
                        "星环 K87 三模机械键盘",
                        null,
                        29900L,
                        0L
                )),
                1,
                false
        ));
        when(productDetailTool.getProductDetail(
                eq(keyboardProductId),
                any(ToolContext.class)
        )).thenReturn(keyboardDetail);
        when(proposeAddCartItemTool.proposeAddCartItem(
                eq(keyboardProductId),
                eq(keyboardSkuId),
                eq(1),
                any(ToolContext.class)
        )).thenReturn(new AgentAddCartActionProposal(
                6101L,
                "ADD_CART_ITEM",
                "PENDING",
                keyboardProductId,
                keyboardSkuId,
                1,
                "星环 K87 三模机械键盘",
                Map.of("轴体", "线性红轴", "颜色", "深空黑"),
                29900L,
                null,
                Instant.now().plusSeconds(600)
        ));

        List<Message> messages = List.of(
                new UserMessage("我想买一款红轴机械键盘，帮我推荐一款"),
                new AssistantMessage(
                        "要帮你把星环 K87 深空黑（线性红轴）¥299加入购物车吗？"
                ),
                new UserMessage("行")
        );
        AgentToolRequestContext requestContext =
                new AgentToolRequestContext(
                        1101L,
                        conversationId,
                        runId,
                        new AgentToolEventChannel(
                                conversationId,
                                runId
                        )
                );

        String response = orchestrator.execute(
                "行",
                messages,
                requestContext
        );

        assertTrue(response.contains("已生成加购待确认卡片"));
        verify(productSearchTool).searchProducts(
                any(String.class),
                isNull(),
                isNull(),
                isNull(),
                eq(ProductSort.LATEST),
                eq(10),
                any(ToolContext.class)
        );
    }

    private AgentProductDetailResult detail(
            Long productId,
            List<AgentProductSkuDetail> skus
    ) {
        String title = productId == 4000L
                ? "星环 K87 三模机械键盘"
                : "星环 H1 无线游戏耳机";

        return new AgentProductDetailResult(
                productId,
                11L,
                title,
                productId == 4000L
                        ? "机械键盘"
                        : "游戏耳机",
                null,
                0L,
                skus,
                false
        );
    }
}
