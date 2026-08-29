package org.example.goshop.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.goshop.agent.entity.AgentMessage;

import java.util.List;
import java.time.LocalDateTime;

/**
 * Agent 可见消息数据访问层。
 *
 * <p>该 Mapper 只负责用户和助手最终可见的消息。
 * 工具调用过程由 AgentToolCallMapper 管理，不能混入这里。</p>
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage>{

    /**
     * 根据会话和浏览器 UUID 查询已经保存的用户消息。
     *
     * <p>发送消息接口发生网络超时后，前端会使用相同
     * clientMessageId 重试。Service 必须先调用该方法：</p>
     *
     * <ul>
     *     <li>已存在：复用原消息和运行；</li>
     *     <li>不存在：才允许创建新消息。</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param clientMessageId 浏览器生成的 UUID
     * @return 已存在的 USER 消息；不存在时返回 null
     */
    @Select("""
            SELECT *
            FROM agent_message
            WHERE conversation_id = #{conversationId}
              AND client_message_id = #{clientMessageId}
              AND role = 'USER'
            LIMIT 1
            """)
    AgentMessage selectByClientMessageId(
            @Param("conversationId") Long conversationId,
            @Param("clientMessageId") String clientMessageId
    );

    /**
     * 查询指定消息是否属于指定会话。
     *
     * <p>后续关联 AgentRun、更新助手消息和组装历史时使用。
     * 不能只根据 messageId 查询后直接信任其会话归属。</p>
     */
    @Select("""
            SELECT *
            FROM agent_message
            WHERE id = #{messageId}
              AND conversation_id = #{conversationId}
            LIMIT 1
            """)
    AgentMessage selectInConversation(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId
    );

    /**
     * 向运行中的助手消息原子追加一张安全结果卡片。
     *
     * <p>JSON_ARRAY_APPEND 在 MySQL 内完成读改写，避免同一次运行连续工具
     * 调用时由应用层“先查后写”覆盖前一张卡片。只有仍为 RUNNING 的运行和
     * STREAMING 助手消息允许写入。</p>
     */
    @Update("""
            UPDATE agent_message message
            INNER JOIN agent_run run_record
                    ON run_record.assistant_message_id = message.id
            SET message.result_cards_json =
                    CASE
                        WHEN message.result_cards_json IS NULL
                            THEN JSON_ARRAY(CAST(#{cardJson} AS JSON))
                        ELSE JSON_ARRAY_APPEND(
                                message.result_cards_json,
                                '$',
                                CAST(#{cardJson} AS JSON)
                        )
                    END
            WHERE run_record.id = #{runId}
              AND run_record.status = 'RUNNING'
              AND message.run_id = run_record.id
              AND message.role = 'ASSISTANT'
              AND message.status = 'STREAMING'
            """)
    int appendResultCard(
            @Param("runId") Long runId,
            @Param("cardJson") String cardJson
    );

    /**
     * 首次打开会话时查询最近一批消息。
     *
     * <p>SQL 按 ID 倒序返回，Service 在响应前需要反转成升序，
     * 让前端按照自然聊天顺序展示。</p>
     *
     * <p>Service 通常传入 limit + 1。多查询的一条用于判断
     * hasMore，返回前端前再移除。</p>
     */
    @Select("""
            SELECT *
            FROM agent_message
            WHERE conversation_id = #{conversationId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> selectLatestMessages(
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );

    /**
     * 查询指定消息之前的历史消息。
     *
     * <p>用于用户在页面向上滚动加载更早记录。</p>
     */
    @Select("""
            SELECT *
            FROM agent_message
            WHERE conversation_id = #{conversationId}
              AND id < #{beforeMessageId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> selectBeforeMessage(
            @Param("conversationId") Long conversationId,
            @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit
    );

    /**
     * 查询可以发送给模型的最近完整消息。
     *
     * <p>严格排除 STREAMING 和 FAILED 消息，防止半条回答或错误提示
     * 进入下一轮模型上下文。</p>
     *
     * <p>这里按消息 ID 倒序查询，Agent Service 之后需要：</p>
     *
     * <ol>
     *     <li>反转为升序；</li>
     *     <li>按 USER + ASSISTANT 组成完整回合；</li>
     *     <li>截取最近 maxHistoryTurns 个完整回合。</li>
     * </ol>
     *
     * @param conversationId 会话 ID
     * @param limit          数据库最大返回条数
     */
    @Select("""
            SELECT *
            FROM agent_message
            WHERE conversation_id = #{conversationId}
              AND status = 'COMPLETED'
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentMessage> selectRecentCompletedMessages(
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );

    /**
     * 把助手占位消息绑定到刚创建的 AgentRun。
     *
     * <p>创建顺序必须是：</p>
     *
     * <ol>
     *     <li>先插入助手占位消息，此时 run_id 为空；</li>
     *     <li>插入引用该助手消息的 agent_run；</li>
     *     <li>再把 run_id 回写到助手消息。</li>
     * </ol>
     *
     * <p>WHERE 条件限制角色必须是 ASSISTANT 且 run_id 仍为空，
     * 防止把用户消息绑定到运行，或者覆盖已有运行关系。</p>
     */
    @Update("""
        UPDATE agent_message
        SET run_id = #{runId}
        WHERE id = #{assistantMessageId}
          AND conversation_id = #{conversationId}
          AND role = 'ASSISTANT'
          AND run_id IS NULL
        """)
    int bindAssistantMessageToRun(
            @Param("assistantMessageId") Long assistantMessageId,
            @Param("conversationId") Long conversationId,
            @Param("runId") Long runId
    );

    /**
     * 把助手占位消息更新为完整回答。
     *
     * <p>只有当前仍为 STREAMING、且确实属于指定 AgentRun 的助手消息
     * 才能完成。这样断流后的重复回调不会覆盖已完成内容。</p>
     */
    @Update("""
        UPDATE agent_message
        SET content = #{content},
            status = 'COMPLETED',
            completed_at = #{completedAt}
        WHERE id = #{assistantMessageId}
          AND conversation_id = #{conversationId}
          AND run_id = #{runId}
          AND role = 'ASSISTANT'
          AND status = 'STREAMING'
        """)
    int completeAssistantMessage(
            @Param("assistantMessageId") Long assistantMessageId,
            @Param("conversationId") Long conversationId,
            @Param("runId") Long runId,
            @Param("content") String content,
            @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * 把流式助手消息更新为失败状态。
     *
     * <p>content 只能传入服务端定义的脱敏提示，
     * 不能保存供应商原始异常、API Key 或完整工具结果。</p>
     */
    @Update("""
        UPDATE agent_message
        SET content = #{safeMessage},
            status = 'FAILED',
            completed_at = #{completedAt}
        WHERE id = #{assistantMessageId}
          AND conversation_id = #{conversationId}
          AND run_id = #{runId}
          AND role = 'ASSISTANT'
          AND status = 'STREAMING'
        """)
    int failAssistantMessage(
            @Param("assistantMessageId") Long assistantMessageId,
            @Param("conversationId") Long conversationId,
            @Param("runId") Long runId,
            @Param("safeMessage") String safeMessage,
            @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * 删除指定会话中所有用户和助手消息。
     *
     * <p>必须先删除 agent_run，因为 agent_run 的 user_message_id 和
     * assistant_message_id 都通过外键引用 agent_message。</p>
     */
    @Delete("""
        DELETE FROM agent_message
        WHERE conversation_id = #{conversationId}
        """)
    int deleteByConversationId(
            @Param("conversationId") Long conversationId
    );
}
