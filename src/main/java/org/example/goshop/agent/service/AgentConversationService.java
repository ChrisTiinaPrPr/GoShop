package org.example.goshop.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.dto.AgentConversationResponse;
import org.example.goshop.agent.dto.AgentMessageCursorQuery;
import org.example.goshop.agent.dto.AgentMessagePageResponse;
import org.example.goshop.agent.dto.AgentMessageResponse;
import org.example.goshop.agent.entity.AgentConversation;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.product.dto.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.goshop.agent.entity.AgentRun;
import org.example.goshop.agent.mapper.AgentActionMapper;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.mapper.AgentToolCallMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 买家购物 Agent 的会话与历史消息服务。
 *
 * <p>当前阶段只负责数据库功能，不调用模型，也不执行任何 Agent 工具。</p>
 *
 * <p>职责包括：</p>
 *
 * <ul>
 *     <li>创建空 Agent 会话；</li>
 *     <li>分页查询当前买家的会话；</li>
 *     <li>校验会话归属；</li>
 *     <li>使用消息 ID 游标查询历史消息；</li>
 *     <li>检查 Agent 业务开关。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    /**
     * 新建会话的默认标题。
     */
    private static final String DEFAULT_CONVERSATION_TITLE = "新会话";

    private final AgentProperties agentProperties;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentRunMapper runMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final AgentActionMapper actionMapper;
    private final AgentResultCardPersistenceService resultCardPersistenceService;

    /**
     * 为当前买家创建一个新的空 Agent 会话。
     *
     * <p>当前接口不接收请求体，userId 只能来自 JWT Authentication。</p>
     *
     * @param userId 当前登录买家 ID
     * @return 新建的会话摘要
     */
    @Transactional
    public AgentConversationResponse createConversation(Long userId) {

        requireEnabled();
        /*
         * 项目数据库字段使用 DATETIME(3)，统一截断到毫秒，
         * 避免 Java 纳秒精度与数据库毫秒精度不一致。
         */
        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setTitle(DEFAULT_CONVERSATION_TITLE);

        /*
         * 空会话还没有任何消息，因此最后消息相关字段必须同时为空。
         * 数据库 CHECK 约束也会验证这条规则。
         */
        conversation.setLastMessageId(null);
        conversation.setLastMessageAt(null);

        /*
         * 虽然数据库设置了默认时间，但主动设置可以保证 insert 后
         * 当前 Java 对象立即拥有完整响应字段，不需要再次查询数据库。
         */
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        int inserted = conversationMapper.insert(conversation);

        if (inserted != 1) {
            throw new BusinessException(
                    50000,
                    "创建 Agent 会话失败，请稍后重试"
            );
        }

        return AgentConversationResponse.from(conversation);
    }

    /**
     * 分页查询当前买家的 Agent 会话。
     *
     * <p>查询条件必须包含 userId。不能先查询所有会话，
     * 再在 Java 中过滤当前用户。</p>
     *
     * @param userId   当前买家 ID
     * @param page     页码，从 1 开始
     * @param pageSize 每页数量
     */
    @Transactional(readOnly = true)
    public PageResult<AgentConversationResponse> listConversations(
            Long userId,
            long page,
            long pageSize
    ) {
        requireEnabled();

        IPage<AgentConversation> conversationPage =
                conversationMapper.selectPage(
                        new Page<>(page, pageSize),
                        new LambdaQueryWrapper<AgentConversation>()
                                .eq(
                                        AgentConversation::getUserId,
                                        userId
                                )
                                /*
                                 * 有消息的会话按最后消息时间倒序。
                                 * MySQL 在 DESC 排序中会把 null 放在后面，
                                 * 所以新建但未发送消息的空会话自然排在最后。
                                 */
                                .orderByDesc(
                                        AgentConversation::getLastMessageAt
                                )
                                /*
                                 * 时间相同时再按雪花 ID 倒序，
                                 * 保证分页顺序稳定。
                                 */
                                .orderByDesc(
                                        AgentConversation::getId
                                )
                );

        List<AgentConversationResponse> records =
                conversationPage.getRecords()
                        .stream()
                        .map(AgentConversationResponse::from)
                        .toList();

        return new PageResult<>(
                records,
                conversationPage.getCurrent(),
                conversationPage.getSize(),
                conversationPage.getTotal()
        );
    }

    /**
     * 查询当前买家指定会话的历史消息。
     *
     * <p>首次请求不传 beforeMessageId，查询最新一页；
     * 向上滚动时传入当前最早消息的 ID。</p>
     *
     * @param userId         当前买家 ID
     * @param conversationId 会话 ID
     * @param query          消息游标和数量
     */
    @Transactional(readOnly = true)
    public AgentMessagePageResponse listMessages(
            Long userId,
            Long conversationId,
            AgentMessageCursorQuery query
    ) {
        requireEnabled();

        /*
         * 先校验会话归属，再查询消息。
         *
         * 如果直接根据 conversationId 查询 agent_message，
         * 攻击者猜到其他人的会话 ID 后可能读取不属于自己的历史。
         */
        requireOwnedConversation(userId, conversationId);

        int limit = query.effectiveLimit();

        /*
         * 多查询一条记录，用于判断是否还有更早消息。
         * 例如前端请求 30 条，数据库实际查询 31 条。
         */
        int databaseLimit = limit + 1;

        List<AgentMessage> queriedMessages;

        if (query.beforeMessageId() == null) {
            queriedMessages = messageMapper.selectLatestMessages(
                    conversationId,
                    databaseLimit
            );
        } else {
            queriedMessages = messageMapper.selectBeforeMessage(
                    conversationId,
                    query.beforeMessageId(),
                    databaseLimit
            );
        }

        boolean hasMore = queriedMessages.size() > limit;
        /*
         * Mapper 按 ID 倒序返回：
         *
         * [最新, ..., 最旧, 多查询的一条]
         *
         * 如果存在第 limit + 1 条，移除最后那条，仅保留前端请求的数量。
         */
        List<AgentMessage> pageMessages = new ArrayList<>(
                queriedMessages.subList(
                        0,
                        Math.min(limit, queriedMessages.size())
                )
        );

        /*
         * 前端聊天页面需要按照：
         *
         * [最旧, ..., 最新]
         *
         * 的自然顺序展示，因此在转换 DTO 前反转列表。
         */
        Collections.reverse(pageMessages);

        List<AgentMessageResponse> items = pageMessages.stream()
                .map(resultCardPersistenceService::toResponse)
                .toList();

        return AgentMessagePageResponse.of(items, hasMore);
    }

    /**
     * 查询并校验会话属于当前买家。
     *
     * <p>不存在和无权访问统一返回 40401，
     * 避免攻击者判断某个会话 ID 是否真实存在。</p>
     */
    private AgentConversation requireOwnedConversation(
            Long userId,
            Long conversationId
    ) {
        AgentConversation conversation =
                conversationMapper.selectOwnedConversation(
                        conversationId,
                        userId
                );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "Agent 会话不存在或无权访问"
            );
        }

        return conversation;
    }

    /**
     * 检查 Agent 业务功能开关。
     *
     * <p>即使 Controller 被错误暴露，Service 仍会执行开关检查。
     * 安全和功能边界不能只依赖前端是否隐藏入口。</p>
     */
    private void requireEnabled() {
        if (!agentProperties.enabled()) {
            throw new BusinessException(
                    50301,
                    "购物助手暂未启用"
            );
        }
    }

    /**
     * 删除当前买家自己的 Agent 会话及全部可还原历史。
     *
     * <p>这里必须使用一个数据库事务完成全部操作。任意一步失败时，
     * Spring 会回滚前面已经执行的 DELETE，避免只删除一部分数据。</p>
     *
     * <p>删除顺序严格遵循外键依赖：</p>
     *
     * <ol>
     *     <li>agent_action：引用 agent_conversation；</li>
     *     <li>agent_tool_call：引用 agent_run；</li>
     *     <li>agent_run：引用 agent_message 和 agent_conversation；</li>
     *     <li>agent_message：引用 agent_conversation；</li>
     *     <li>agent_conversation：最后删除会话主记录。</li>
     * </ol>
     *
     * @param userId         JWT 中的当前买家 ID
     * @param conversationId 要删除的会话 ID
     */
    @Transactional
    public void deleteConversation(
            Long userId,
            Long conversationId
    ) {
        requireEnabled();

        /*
         * 先锁定属于当前用户的会话行。
         *
         * AgentRunInitializationService 创建新运行时也会锁定同一会话行，
         * 因此“开始生成”和“删除会话”会串行执行，不会出现删除到一半又
         * 插入新消息或新运行的情况。
         */
        AgentConversation conversation =
                conversationMapper
                        .selectOwnedConversationForUpdate(
                                conversationId,
                                userId
                        );

        if (conversation == null) {
            /*
             * 不区分“会话不存在”和“属于其他用户”，避免攻击者通过不同
             * 错误信息探测其他用户的会话 ID。
             */
            throw new BusinessException(
                    40401,
                    "Agent 会话不存在或无权访问"
            );
        }

        /*
         * SSE 仍在生成时禁止删除。
         *
         * 不能先取消模型再删除，因为当前接口的含义只是“删除历史”，
         * 不应该隐式承担终止模型任务的职责。
         */
        AgentRun running =
                runMapper.selectRunningByConversationId(
                        conversationId
                );

        if (running != null) {
            throw new BusinessException(
                    40901,
                    "当前会话正在生成回复，请等待完成后再删除"
            );
        }

        /*
         * 子表删除数量允许为 0，例如空会话没有消息、运行、工具或动作，
         * 这不属于异常。
         */
        actionMapper.deleteByConversationId(
                conversationId
        );

        toolCallMapper.deleteByConversationId(
                conversationId
        );

        runMapper.deleteByConversationId(
                conversationId
        );

        messageMapper.deleteByConversationId(
                conversationId
        );

        /*
         * 最终删除必须仍然同时匹配 conversationId 和 userId。
         * 正常情况下应该精确删除一行。
         */
        int deleted =
                conversationMapper.deleteOwnedConversation(
                        conversationId,
                        userId
                );

        if (deleted != 1) {
            throw new BusinessException(
                    50000,
                    "删除 Agent 会话失败，请稍后重试"
            );
        }
    }
}
