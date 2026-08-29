package org.example.goshop.agent.tool.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.agent.entity.AgentToolCall;
import org.example.goshop.agent.dto.AgentSseEvent;
import org.example.goshop.agent.dto.AgentSseEventType;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.agent.service.AgentActionService;
import org.example.goshop.agent.service.AgentToolAuditService;
import org.example.goshop.agent.service.model.AgentAddCartActionProposal;
import org.example.goshop.agent.service.model.AgentToolCallHandle;
import org.example.goshop.agent.tool.AgentToolEventChannel;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加购提案工具的当前 Run 商品详情校验测试。
 */
class ProposeAddCartItemToolTest {

    private static final long USER_ID = 1001L;
    private static final long CONVERSATION_ID = 2001L;
    private static final long RUN_ID = 3001L;
    private static final long PRODUCT_ID = 920000003L;
    private static final long PURPLE_SKU_ID = 930000006L;

    private AgentActionService actionService;
    private AgentToolAuditService toolAuditService;
    private AgentToolCallMapper toolCallMapper;
    private ProposeAddCartItemTool tool;
    private ToolContext toolContext;
    private AgentToolEventChannel eventChannel;

    @BeforeEach
    void setUp() {
        actionService = mock(AgentActionService.class);
        toolAuditService = mock(AgentToolAuditService.class);
        toolCallMapper = mock(AgentToolCallMapper.class);

        tool = new ProposeAddCartItemTool(
                actionService,
                toolAuditService,
                toolCallMapper,
                new ObjectMapper()
        );

        eventChannel =
                new AgentToolEventChannel(
                        CONVERSATION_ID,
                        RUN_ID
                );

        AgentToolRequestContext requestContext =
                new AgentToolRequestContext(
                        USER_ID,
                        CONVERSATION_ID,
                        RUN_ID,
                        eventChannel
                );

        toolContext = new ToolContext(
                requestContext.toMap()
        );

        when(toolAuditService.start(
                any(AgentToolRequestContext.class),
                eq("propose_add_cart_item"),
                org.mockito.ArgumentMatchers
                        .<Map<String, Object>>any()
        )).thenReturn(new AgentToolCallHandle(
                4001L,
                RUN_ID,
                "proposal-call-1",
                "propose_add_cart_item",
                System.nanoTime()
        ));
    }

    @Test
    void shouldRejectProposalWhenCurrentRunHasNoSuccessfulDetail() {
        when(toolCallMapper.selectLatestSuccessfulInRun(
                RUN_ID,
                "get_product_detail"
        )).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tool.proposeAddCartItem(
                        PRODUCT_ID,
                        PURPLE_SKU_ID,
                        1,
                        toolContext
                )
        );

        assertEquals(40901, exception.getCode());
        verify(actionService, never())
                .createPendingAddCartAction(
                        any(), any(), any(), any(), any()
                );
    }

    @Test
    void shouldRejectProductOrSkuNotReturnedByCurrentRunDetail() {
        when(toolCallMapper.selectLatestSuccessfulInRun(
                RUN_ID,
                "get_product_detail"
        )).thenReturn(successfulDetailCall(
                """
                {
                  "productId": 920000003,
                  "skuIds": [930000005, 930000006]
                }
                """
        ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> tool.proposeAddCartItem(
                        PRODUCT_ID,
                        1L,
                        1,
                        toolContext
                )
        );

        assertEquals(40901, exception.getCode());
        verify(actionService, never())
                .createPendingAddCartAction(
                        any(), any(), any(), any(), any()
                );
    }

    @Test
    void shouldCreatePendingActionForVerifiedPurpleSku() {
        when(toolCallMapper.selectLatestSuccessfulInRun(
                RUN_ID,
                "get_product_detail"
        )).thenReturn(successfulDetailCall(
                """
                {
                  "productId": 920000003,
                  "skuIds": [930000005, 930000006]
                }
                """
        ));

        AgentAddCartActionProposal proposal =
                new AgentAddCartActionProposal(
                        5001L,
                        "ADD_CART_ITEM",
                        "PENDING",
                        PRODUCT_ID,
                        PURPLE_SKU_ID,
                        1,
                        "星环 H1 无线游戏耳机",
                        Map.of(
                                "颜色",
                                "星云紫",
                                "麦克风",
                                "可拆卸"
                        ),
                        1L,
                        null,
                        Instant.now().plusSeconds(600)
                );

        when(actionService.createPendingAddCartAction(
                USER_ID,
                CONVERSATION_ID,
                PRODUCT_ID,
                PURPLE_SKU_ID,
                1
        )).thenReturn(proposal);

        AgentAddCartActionProposal result =
                tool.proposeAddCartItem(
                        PRODUCT_ID,
                        PURPLE_SKU_ID,
                        1,
                        toolContext
                );

        assertEquals(5001L, result.actionId());
        assertEquals("PENDING", result.status());
        assertTrue(result.specifications()
                .containsValue("星云紫"));

        /*
         * ACTION_REQUIRED 必须位于 propose 工具完成事件之前。
         * 前端只有收到该结构化事件才会创建真实确认卡片。
         */
        List<AgentSseEvent<?>> events =
                eventChannel.events()
                        .take(3)
                        .collectList()
                        .block(Duration.ofSeconds(1));

        assertEquals(
                List.of(
                        AgentSseEventType.TOOL_STARTED,
                        AgentSseEventType.ACTION_REQUIRED,
                        AgentSseEventType.TOOL_COMPLETED
                ),
                events.stream()
                        .map(AgentSseEvent::type)
                        .toList()
        );

        verify(actionService)
                .createPendingAddCartAction(
                        USER_ID,
                        CONVERSATION_ID,
                        PRODUCT_ID,
                        PURPLE_SKU_ID,
                        1
                );
    }

    private AgentToolCall successfulDetailCall(
            String resultSummaryJson
    ) {
        AgentToolCall call = new AgentToolCall();
        call.setResultSummaryJson(resultSummaryJson);
        return call;
    }
}
