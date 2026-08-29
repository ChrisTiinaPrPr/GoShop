package org.example.goshop.agent.service;

import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.entity.AgentMessageRole;
import org.example.goshop.agent.entity.AgentMessageStatus;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.service.model.AgentHistoryTurn;
import org.example.goshop.agent.service.model.AgentRunInitialization;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 构建发送给大模型的对话上下文。
 *
 * <p>该 Service 是应用数据库消息与 Spring AI Message 之间的适配层。</p>
 *
 * <p>最终返回顺序为：</p>
 *
 * <pre>
 * 历史 USER
 * 历史 ASSISTANT
 * 历史 USER
 * 历史 ASSISTANT
 * 当前 USER
 * </pre>
 *
 * <p>系统提示词不在这里添加，后续由专门的 Prompt 构建层添加。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentConversationContextService {

    private final AgentProperties agentProperties;
    private final AgentRunMapper runMapper;

    /**
     * 构建当前新运行需要发送给模型的消息窗口。
     *
     * @param initialization 初始化事务返回的运行上下文
     * @return 按自然对话顺序排列的 Spring AI 消息
     */
    @Transactional(readOnly = true)
    public List<Message> buildModelMessages(
            AgentRunInitialization initialization
    ) {
        Objects.requireNonNull(
                initialization,
                "AgentRunInitialization 不能为空"
        );

        /*
         * replayed=true 代表 clientMessageId 已经存在。
         *
         * 幂等重试绝不能再次构建 Prompt 并调用模型，否则可能重复调用
         * 商品、订单或加购动作工具。
         */
        if (!initialization.newlyCreated()) {
            throw new BusinessException(
                    40901,
                    "幂等重试不能再次调用模型"
            );
        }

        AgentMessage currentUserMessage =
                initialization.userMessage();

        validateCurrentUserMessage(
                initialization,
                currentUserMessage
        );

        /*
         * Mapper 返回最近 maxHistoryTurns 个完整回合。
         *
         * 默认值为 10，即最多：
         * 10 条历史用户消息 + 10 条历史助手消息 + 1 条当前用户消息。
         */
        List<AgentHistoryTurn> turns =
                runMapper.selectRecentCompletedTurns(
                        initialization.run()
                                .getConversationId(),
                        currentUserMessage.getId(),
                        agentProperties.maxHistoryTurns()
                );

        /*
         * SQL 为了高效取得“最近 N 条”使用倒序查询。
         * 模型需要看到从旧到新的自然对话顺序，因此创建副本后反转。
         */
        List<AgentHistoryTurn> chronologicalTurns =
                new ArrayList<>(turns);

        Collections.reverse(chronologicalTurns);

        List<Message> modelMessages =
                new ArrayList<>(
                        chronologicalTurns.size() * 2 + 1
                );

        for (AgentHistoryTurn turn :
                chronologicalTurns) {
            validateHistoryTurn(turn);

            /*
             * UserMessage 和 AssistantMessage 是 Spring AI 的标准消息类型。
             *
             * ChatClient 会根据消息类型把它们转换成供应商协议中的
             * role=user 和 role=assistant。
             */
            modelMessages.add(
                    new UserMessage(
                            turn.getUserContent()
                    )
            );

            modelMessages.add(
                    new AssistantMessage(
                            turn.getAssistantContent()
                    )
            );
        }

        /*
         * 当前用户消息必须放在最后。
         *
         * 当前助手消息仍然只是数据库中的 STREAMING 占位消息，
         * 不能加入模型上下文。
         */
        modelMessages.add(
                new UserMessage(
                        currentUserMessage.getContent()
                )
        );

        /*
         * 返回不可修改列表，防止后续编排代码意外删除历史消息，
         * 或在多个订阅者之间修改同一个上下文对象。
         */
        return List.copyOf(modelMessages);
    }

    /**
     * 验证初始化结果中的当前用户消息。
     */
    private void validateCurrentUserMessage(
            AgentRunInitialization initialization,
            AgentMessage currentUserMessage
    ) {
        if (currentUserMessage == null
                || currentUserMessage.getRole()
                != AgentMessageRole.USER
                || currentUserMessage.getStatus()
                != AgentMessageStatus.COMPLETED
                || !Objects.equals(
                currentUserMessage.getConversationId(),
                initialization.run()
                        .getConversationId()
        )
                || !Objects.equals(
                currentUserMessage.getId(),
                initialization.run()
                        .getUserMessageId()
        )
                || currentUserMessage.getContent() == null
                || currentUserMessage.getContent()
                .isBlank()) {
            throw new BusinessException(
                    50000,
                    "当前 Agent 用户消息数据不完整"
            );
        }
    }

    /**
     * 验证数据库返回的历史完整回合。
     */
    private void validateHistoryTurn(
            AgentHistoryTurn turn
    ) {
        if (turn == null
                || turn.getUserMessageId() == null
                || turn.getAssistantMessageId() == null
                || turn.getUserContent() == null
                || turn.getUserContent().isBlank()
                || turn.getAssistantContent() == null
                || turn.getAssistantContent().isBlank()) {
            /*
             * 不要静默跳过损坏数据。
             *
             * 静默跳过可能让模型在缺少关键上下文时作出错误判断，
             * 应该让本次运行失败并记录内部数据问题。
             */
            throw new BusinessException(
                    50000,
                    "Agent 历史回合数据不完整"
            );
        }
    }
}
