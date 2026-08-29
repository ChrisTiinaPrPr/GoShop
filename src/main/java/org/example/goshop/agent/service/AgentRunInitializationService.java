package org.example.goshop.agent.service;

import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.dto.AgentSendMessageRequest;
import org.example.goshop.agent.entity.*;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.service.model.AgentModelIdentity;
import org.example.goshop.agent.service.model.AgentRunInitialization;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 初始化一次 Agent 模型运行。
 *
 * <p>这个 Service 只负责可靠地创建数据库状态，不负责调用模型。
 * 整个初始化过程位于同一个数据库事务中。</p>
 *
 * <p>一条新请求会创建：</p>
 *
 * <ol>
 *     <li>一条 USER + COMPLETED 用户消息；</li>
 *     <li>一条 ASSISTANT + STREAMING 助手占位消息；</li>
 *     <li>一条 RUNNING 状态的 AgentRun；</li>
 *     <li>助手消息与 AgentRun 的关联关系。</li>
 * </ol>
 *
 * <p>其中任意一步失败，整个事务都会回滚，不能留下只有用户消息、
 * 没有运行记录的半成品数据。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunInitializationService {

    /**
     * 会话标题最多取首条用户消息的 30 个 Unicode 字符。
     */
    private static final int MAX_TITLE_CODE_POINTS = 30;

    private final AgentProperties agentProperties;
    private final AgentMessageRateLimitService messageRateLimitService;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentRunMapper runMapper;

    /**
     * 创建或复用一次 AgentRun。
     *
     * @param userId         JWT 中的当前买家 ID
     * @param conversationId 会话 ID
     * @param request        用户消息及浏览器幂等 UUID
     * @param modelIdentity  后续实际调用的供应商类型和模型名
     * @return 新建或复用的完整运行上下文
     */
    @Transactional
    public AgentRunInitialization initialize(
            Long userId,
            Long conversationId,
            AgentSendMessageRequest request,
            AgentModelIdentity modelIdentity
    ) {
        requireEnabled();

        Objects.requireNonNull(
                modelIdentity,
                "模型身份不能为空"
        );

        /*
         * Controller 的 Jakarta Validation 是第一层校验。
         * Service 必须再次执行核心业务校验，避免内部调用绕过 Controller。
         */
        String normalizedContent = normalizeAndValidateContent(
                request == null ? null : request.content()
        );

        String normalizedClientMessageId =
                normalizeClientMessageId(
                        request == null
                                ? null
                                : request.clientMessageId()
                );

        /*
         * 必须在任何 Agent 消息、占位消息和运行记录落库前完成限流。
         * 超限请求不会制造半条历史；相同 clientMessageId 的断流重试由
         * 限流脚本识别为同一请求，不会重复消耗额度。
         */
        messageRateLimitService.checkAllowed(
                userId,
                conversationId,
                normalizedClientMessageId
        );

        /*
         * 锁定会话行有三个作用：
         *
         * 1. 同时校验会话属于当前用户；
         * 2. 串行化同一会话中的发送操作；
         * 3. 避免两个请求同时创建 RUNNING 运行。
         *
         * 不同会话之间不会互相阻塞。
         */
        AgentConversation conversation =
                conversationMapper.selectOwnedConversationForUpdate(
                        conversationId,
                        userId
                );

        if (conversation == null) {
            /*
             * 不区分“不存在”和“属于其他用户”，
             * 避免攻击者通过错误差异探测他人的会话 ID。
             */
            throw new BusinessException(
                    40401,
                    "Agent 会话不存在或无权访问"
            );
        }

        /*
         * 必须先处理 clientMessageId 幂等，再检查当前运行。
         *
         * 假设用户第一次请求已成功创建 RUNNING 记录，但浏览器断流后
         * 使用同一个 clientMessageId 重试，此时应该复用原运行，
         * 不能因为发现 RUNNING 就把它当成另一条冲突请求。
         */
        AgentMessage existingUserMessage =
                messageMapper.selectByClientMessageId(
                        conversationId,
                        normalizedClientMessageId
                );

        if (existingUserMessage != null) {
            return restoreExistingInitialization(
                    userId,
                    conversationId,
                    normalizedContent,
                    existingUserMessage
            );
        }

        /*
         * clientMessageId 是新的，但会话已经有另一条正在运行的请求。
         *
         * 首期不允许同一个会话同时生成两条回复，否则模型上下文顺序、
         * lastMessageId 和前端流式展示都会变得不确定。
         */
        AgentRun running =
                runMapper.selectRunningByConversationId(
                        conversationId
                );

        if (running != null) {
            throw new BusinessException(
                    40901,
                    "当前会话已有消息正在生成，请等待完成后再发送"
            );
        }

        return createNewInitialization(
                userId,
                conversation,
                normalizedClientMessageId,
                normalizedContent,
                modelIdentity
        );
    }

    /**
     * 恢复相同 clientMessageId 对应的旧运行。
     */
    private AgentRunInitialization restoreExistingInitialization(
            Long userId,
            Long conversationId,
            String normalizedContent,
            AgentMessage userMessage
    ) {
        /*
         * 同一个 UUID 不允许表示两段不同正文。
         *
         * 如果不校验，浏览器或攻击者可以用旧 UUID 提交新正文，
         * 服务端却返回旧回答，造成前后端消息语义错乱。
         */
        if (!Objects.equals(
                userMessage.getContent(),
                normalizedContent
        )) {
            throw new BusinessException(
                    40901,
                    "clientMessageId 已被另一条消息使用"
            );
        }

        AgentRun run =
                runMapper.selectByUserMessageId(
                        userMessage.getId()
                );

        if (run == null) {
            /*
             * 正常事务不会产生这种情况。
             *
             * 出现时说明数据库可能存在人工修改或旧版本遗留的
             * 不完整数据，不能再次创建运行，否则可能重复调用工具。
             */
            throw new BusinessException(
                    50000,
                    "Agent 消息运行数据不完整"
            );
        }

        AgentMessage assistantMessage =
                messageMapper.selectInConversation(
                        conversationId,
                        run.getAssistantMessageId()
                );

        if (assistantMessage == null
                || assistantMessage.getRole()
                != AgentMessageRole.ASSISTANT) {
            throw new BusinessException(
                    50000,
                    "Agent 助手消息数据不完整"
            );
        }

        /*
         * replayed=true 告诉后续流式编排层：
         *
         * - 不能再次调用模型；
         * - COMPLETED 时重放已保存回答；
         * - FAILED/TIMED_OUT 时重放原失败状态；
         * - RUNNING 时尝试订阅原运行的实时流。
         */
        return new AgentRunInitialization(
                userId,
                userMessage,
                assistantMessage,
                run,
                true
        );
    }

    /**
     * 创建一套新的用户消息、助手占位消息和运行记录。
     */
    private AgentRunInitialization createNewInitialization(
            Long userId,
            AgentConversation conversation,
            String clientMessageId,
            String content,
            AgentModelIdentity modelIdentity
    ) {
        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);

        AgentMessage userMessage = buildUserMessage(
                conversation.getId(),
                clientMessageId,
                content,
                now
        );

        requireOneRow(
                messageMapper.insert(userMessage),
                "保存 Agent 用户消息失败"
        );

        AgentMessage assistantMessage =
                buildAssistantPlaceholder(
                        conversation.getId(),
                        now
                );

        requireOneRow(
                messageMapper.insert(assistantMessage),
                "创建 Agent 助手消息失败"
        );

        AgentRun run = buildRun(
                conversation.getId(),
                userMessage.getId(),
                assistantMessage.getId(),
                modelIdentity,
                now
        );

        requireOneRow(
                runMapper.insert(run),
                "创建 Agent 运行记录失败"
        );

        /*
         * AgentRun 插入成功后才拥有 runId，
         * 因此现在才能把 runId 回写到助手占位消息。
         */
        requireOneRow(
                messageMapper.bindAssistantMessageToRun(
                        assistantMessage.getId(),
                        conversation.getId(),
                        run.getId()
                ),
                "绑定 Agent 助手消息失败"
        );

        /*
         * 同步更新内存对象，确保返回给后续编排层的对象
         * 与数据库中的 run_id 保持一致。
         */
        assistantMessage.setRunId(run.getId());

        /*
         * 标题只会从“新会话”初始化一次。
         * 重试和后续消息都不会覆盖已有标题。
         */
        conversationMapper.initializeTitle(
                conversation.getId(),
                userId,
                buildConversationTitle(content)
        );

        /*
         * 用户消息已经完整落库，因此先把它作为会话最后消息。
         *
         * 等助手回答成功后，完成事务会再把 last_message_id
         * 推进到助手消息。流式生成失败时，用户消息仍能正常显示。
         */
        requireOneRow(
                conversationMapper.advanceLastMessage(
                        conversation.getId(),
                        userMessage.getId(),
                        now
                ),
                "更新 Agent 会话最后消息失败"
        );

        return new AgentRunInitialization(
                userId,
                userMessage,
                assistantMessage,
                run,
                false
        );
    }

    /**
     * 创建已经完成的用户消息。
     */
    private AgentMessage buildUserMessage(
            Long conversationId,
            String clientMessageId,
            String content,
            LocalDateTime now
    ) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(AgentMessageRole.USER);
        message.setContent(content);
        message.setStatus(AgentMessageStatus.COMPLETED);
        message.setClientMessageId(clientMessageId);
        message.setRunId(null);
        message.setCreatedAt(now);
        message.setCompletedAt(now);
        return message;
    }

    /**
     * 创建等待模型填充内容的助手占位消息。
     */
    private AgentMessage buildAssistantPlaceholder(
            Long conversationId,
            LocalDateTime now
    ) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(AgentMessageRole.ASSISTANT);

        /*
         * 数据库 content 为 NOT NULL。
         * 流式开始前使用空字符串，而不是 null。
         */
        message.setContent("");
        message.setStatus(AgentMessageStatus.STREAMING);
        message.setClientMessageId(null);
        message.setRunId(null);
        message.setCreatedAt(now);
        message.setCompletedAt(null);
        return message;
    }

    /**
     * 创建 RUNNING 状态的运行记录。
     */
    private AgentRun buildRun(
            Long conversationId,
            Long userMessageId,
            Long assistantMessageId,
            AgentModelIdentity modelIdentity,
            LocalDateTime now
    ) {
        AgentRun run = new AgentRun();
        run.setConversationId(conversationId);
        run.setUserMessageId(userMessageId);
        run.setAssistantMessageId(assistantMessageId);
        run.setProvider(modelIdentity.provider());
        run.setModel(modelIdentity.model());
        run.setStatus(AgentRunStatus.RUNNING);

        /*
         * Token、延迟和耗时只能在模型开始返回或运行结束后确定。
         */
        run.setPromptTokens(null);
        run.setCompletionTokens(null);
        run.setFirstTokenMs(null);
        run.setDurationMs(null);
        run.setErrorCode(null);

        run.setCreatedAt(now);
        run.setFinishedAt(null);
        return run;
    }

    /**
     * 校验并标准化用户正文。
     */
    private String normalizeAndValidateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(
                    40001,
                    "消息内容不能为空"
            );
        }

        String normalized = content.strip();

        /*
         * String.length() 统计 UTF-16 单元，一个 emoji 可能计为 2。
         * codePointCount 更接近用户理解的字符数量。
         */
        int codePointCount = normalized.codePointCount(
                0,
                normalized.length()
        );

        if (codePointCount > agentProperties.maxInputChars()) {
            throw new BusinessException(
                    40001,
                    "消息内容不能超过 "
                            + agentProperties.maxInputChars()
                            + " 个字符"
            );
        }

        return normalized;
    }

    /**
     * 校验并统一 UUID 格式。
     */
    private String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null
                || clientMessageId.isBlank()) {
            throw new BusinessException(
                    40001,
                    "clientMessageId 不能为空"
            );
        }

        String normalized = clientMessageId
                .strip()
                .toLowerCase(Locale.ROOT);

        try {
            UUID uuid = UUID.fromString(normalized);

            /*
             * UUID.fromString 对部分非标准缩写形式比较宽松，
             * 再比较一次字符串可确保必须是标准的 36 字符 UUID。
             */
            if (!uuid.toString().equals(normalized)) {
                throw new IllegalArgumentException();
            }

            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    40001,
                    "clientMessageId 必须是标准 UUID"
            );
        }
    }

    /**
     * 从用户首条消息生成单行会话标题。
     */
    private String buildConversationTitle(String content) {
        /*
         * 标题中不保留换行和连续空白，避免会话列表高度异常。
         */
        String singleLine = content
                .replaceAll("\\s+", " ")
                .strip();

        int codePointCount = singleLine.codePointCount(
                0,
                singleLine.length()
        );

        if (codePointCount <= MAX_TITLE_CODE_POINTS) {
            return singleLine;
        }

        int endIndex = singleLine.offsetByCodePoints(
                0,
                MAX_TITLE_CODE_POINTS
        );

        return singleLine.substring(0, endIndex);
    }

    /**
     * 验证数据库写操作必须恰好影响一行。
     */
    private void requireOneRow(
            int affectedRows,
            String message
    ) {
        if (affectedRows != 1) {
            throw new BusinessException(
                    50000,
                    message
            );
        }
    }

    /**
     * 服务端必须独立检查 Agent 开关。
     */
    private void requireEnabled() {
        if (!agentProperties.enabled()) {
            throw new BusinessException(
                    50301,
                    "购物助手暂未启用"
            );
        }
    }

}
