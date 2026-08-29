package org.example.goshop.agent.service;

import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.entity.AgentConversation;
import org.example.goshop.agent.entity.AgentRun;
import org.example.goshop.agent.entity.AgentRunStatus;
import org.example.goshop.agent.mapper.AgentActionMapper;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 会话删除规则的单元测试。
 *
 * <p>本测试不连接真实数据库，也不调用模型。所有 Mapper 都使用 Mockito
 * 替身，专门验证删除前的权限/运行状态校验以及外键依赖删除顺序。</p>
 *
 * <p>真实数据库的事务回滚由 Spring 的 {@code @Transactional} 保证；
 * 单元测试负责确保 Service 不会跳过业务校验，也不会颠倒删除顺序。</p>
 */
class AgentConversationServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long CONVERSATION_ID = 2001L;

    private AgentConversationMapper conversationMapper;
    private AgentMessageMapper messageMapper;
    private AgentRunMapper runMapper;
    private AgentToolCallMapper toolCallMapper;
    private AgentActionMapper actionMapper;
    private AgentResultCardPersistenceService resultCardPersistenceService;
    private AgentConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(
                AgentConversationMapper.class
        );
        messageMapper = mock(AgentMessageMapper.class);
        runMapper = mock(AgentRunMapper.class);
        toolCallMapper = mock(AgentToolCallMapper.class);
        actionMapper = mock(AgentActionMapper.class);
        resultCardPersistenceService = mock(
                AgentResultCardPersistenceService.class
        );

        /*
         * 删除会话属于 Agent 功能的一部分，因此测试配置必须启用 Agent。
         * 其他数值使用 application.yml 对应的合法范围；这些配置不会让
         * 单元测试访问真实模型或外部服务。
         */
        AgentProperties properties = new AgentProperties(
                true,
                10,
                10,
                8,
                45,
                10,
                1000,
                1200
        );

        conversationService = new AgentConversationService(
                properties,
                conversationMapper,
                messageMapper,
                runMapper,
                toolCallMapper,
                actionMapper,
                resultCardPersistenceService
        );
    }

    /**
     * 验证正常删除必须严格遵循数据库外键依赖顺序。
     */
    @Test
    void shouldDeleteOwnedConversationInForeignKeyOrder() {
        AgentConversation conversation = ownedConversation();

        when(conversationMapper
                .selectOwnedConversationForUpdate(
                        CONVERSATION_ID,
                        USER_ID
                ))
                .thenReturn(conversation);

        /*
         * 返回 null 表示当前没有 RUNNING 运行，可以开始删除。
         */
        when(runMapper.selectRunningByConversationId(
                CONVERSATION_ID
        )).thenReturn(null);

        /*
         * 子表删除数量可能为 0（例如空会话），只有会话主记录必须恰好
         * 删除一行。因此只需要显式模拟主记录删除成功。
         */
        when(conversationMapper.deleteOwnedConversation(
                CONVERSATION_ID,
                USER_ID
        )).thenReturn(1);

        assertDoesNotThrow(
                () -> conversationService.deleteConversation(
                        USER_ID,
                        CONVERSATION_ID
                )
        );

        /*
         * agent_run 同时引用 agent_message 和 agent_conversation，
         * agent_tool_call 又引用 agent_run，所以顺序不能互换。
         */
        InOrder order = inOrder(
                conversationMapper,
                runMapper,
                actionMapper,
                toolCallMapper,
                messageMapper
        );

        order.verify(conversationMapper)
                .selectOwnedConversationForUpdate(
                        CONVERSATION_ID,
                        USER_ID
                );
        order.verify(runMapper)
                .selectRunningByConversationId(
                        CONVERSATION_ID
                );
        order.verify(actionMapper)
                .deleteByConversationId(CONVERSATION_ID);
        order.verify(toolCallMapper)
                .deleteByConversationId(CONVERSATION_ID);
        order.verify(runMapper)
                .deleteByConversationId(CONVERSATION_ID);
        order.verify(messageMapper)
                .deleteByConversationId(CONVERSATION_ID);
        order.verify(conversationMapper)
                .deleteOwnedConversation(
                        CONVERSATION_ID,
                        USER_ID
                );
    }

    /**
     * SSE 仍在生成时必须返回 40901，并且不能执行任何 DELETE。
     */
    @Test
    void shouldRejectDeletionWhileRunIsRunning() {
        when(conversationMapper
                .selectOwnedConversationForUpdate(
                        CONVERSATION_ID,
                        USER_ID
                ))
                .thenReturn(ownedConversation());

        AgentRun running = new AgentRun();
        running.setId(3001L);
        running.setConversationId(CONVERSATION_ID);
        running.setStatus(AgentRunStatus.RUNNING);

        when(runMapper.selectRunningByConversationId(
                CONVERSATION_ID
        )).thenReturn(running);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> conversationService.deleteConversation(
                        USER_ID,
                        CONVERSATION_ID
                )
        );

        assertEquals(40901, exception.getCode());
        assertEquals(
                "当前会话正在生成回复，请等待完成后再删除",
                exception.getMessage()
        );

        verify(actionMapper, never())
                .deleteByConversationId(CONVERSATION_ID);
        verify(toolCallMapper, never())
                .deleteByConversationId(CONVERSATION_ID);
        verify(runMapper, never())
                .deleteByConversationId(CONVERSATION_ID);
        verify(messageMapper, never())
                .deleteByConversationId(CONVERSATION_ID);
        verify(conversationMapper, never())
                .deleteOwnedConversation(
                        CONVERSATION_ID,
                        USER_ID
                );
    }

    /**
     * 会话不存在和跨用户访问统一返回 40401，避免泄露会话是否存在。
     */
    @Test
    void shouldHideWhetherConversationExistsForCurrentUser() {
        when(conversationMapper
                .selectOwnedConversationForUpdate(
                        CONVERSATION_ID,
                        USER_ID
                ))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> conversationService.deleteConversation(
                        USER_ID,
                        CONVERSATION_ID
                )
        );

        assertEquals(40401, exception.getCode());
        assertEquals(
                "Agent 会话不存在或无权访问",
                exception.getMessage()
        );

        /*
         * 归属校验失败后不能继续读取运行，更不能触碰任何子表。
         */
        verifyNoInteractions(
                runMapper,
                actionMapper,
                toolCallMapper,
                messageMapper
        );
        verify(conversationMapper, never())
                .deleteOwnedConversation(
                        CONVERSATION_ID,
                        USER_ID
                );
    }

    /**
     * 所有子表处理完成后，若会话主记录没有精确删除一行，应抛出
     * 50000。真实运行中该异常会触发事务回滚，恢复前面的子表删除。
     */
    @Test
    void shouldFailWhenConversationRowWasNotDeleted() {
        when(conversationMapper
                .selectOwnedConversationForUpdate(
                        CONVERSATION_ID,
                        USER_ID
                ))
                .thenReturn(ownedConversation());
        when(runMapper.selectRunningByConversationId(
                CONVERSATION_ID
        )).thenReturn(null);
        when(conversationMapper.deleteOwnedConversation(
                CONVERSATION_ID,
                USER_ID
        )).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> conversationService.deleteConversation(
                        USER_ID,
                        CONVERSATION_ID
                )
        );

        assertEquals(50000, exception.getCode());
        assertEquals(
                "删除 Agent 会话失败，请稍后重试",
                exception.getMessage()
        );

        /*
         * 确认失败发生在最后一步，而不是因为某个子表被漏删。
         */
        verify(actionMapper)
                .deleteByConversationId(CONVERSATION_ID);
        verify(toolCallMapper)
                .deleteByConversationId(CONVERSATION_ID);
        verify(runMapper)
                .deleteByConversationId(CONVERSATION_ID);
        verify(messageMapper)
                .deleteByConversationId(CONVERSATION_ID);
    }

    private AgentConversation ownedConversation() {
        AgentConversation conversation =
                new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setTitle("删除功能测试会话");
        return conversation;
    }
}
