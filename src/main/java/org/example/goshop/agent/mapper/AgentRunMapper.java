package org.example.goshop.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.agent.service.model.AgentHistoryTurn;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.time.LocalDateTime;

/**
 * Agent 模型运行数据访问层。
 *
 * <p>一条用户消息最多对应一个 AgentRun，数据库已经通过
 * user_message_id 唯一索引保证这个约束。</p>
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 查询指定用户消息对应的运行记录。
     *
     * <p>当浏览器使用相同 clientMessageId 重试时，Service 会先找到
     * 原来的用户消息，再通过该方法找回原来的 AgentRun，避免再次调用模型。</p>
     *
     * @param userMessageId 用户消息 ID
     * @return 对应运行；尚未创建时返回 null
     */
    @Select("""
            SELECT *
            FROM agent_run
            WHERE user_message_id = #{userMessageId}
            LIMIT 1
            """)
    AgentRun selectByUserMessageId(
            @Param("userMessageId") Long userMessageId
    );

    /**
     * 查询指定会话当前正在进行的运行。
     *
     * <p>数据库生成列和唯一索引已经保证一个会话最多只有一个
     * RUNNING 记录。该查询主要用于返回清晰的业务错误和恢复状态。</p>
     */
    @Select("""
            SELECT *
            FROM agent_run
            WHERE conversation_id = #{conversationId}
              AND status = 'RUNNING'
            LIMIT 1
            """)
    AgentRun selectRunningByConversationId(
            @Param("conversationId") Long conversationId
    );

    /**
     * 查询并锁定运行记录。
     *
     * <p>必须在事务中调用。成功、失败和超时收口都先锁定运行行，
     * 防止模型完成事件与超时任务同时修改同一个 AgentRun。</p>
     */
    @Select("""
        SELECT *
        FROM agent_run
        WHERE id = #{runId}
        FOR UPDATE
        """)
    AgentRun selectByIdForUpdate(
            @Param("runId") Long runId
    );

    /**
     * 把 RUNNING 运行更新为 COMPLETED。
     *
     * <p>WHERE status = 'RUNNING' 是状态机保护：
     * 已失败、已超时或已经完成的运行不能被再次完成。</p>
     */
    @Update("""
        UPDATE agent_run
        SET status = 'COMPLETED',
            prompt_tokens = #{promptTokens},
            completion_tokens = #{completionTokens},
            first_token_ms = #{firstTokenMs},
            duration_ms = #{durationMs},
            error_code = NULL,
            finished_at = #{finishedAt}
        WHERE id = #{runId}
          AND status = 'RUNNING'
        """)
    int completeRun(
            @Param("runId") Long runId,
            @Param("promptTokens") Integer promptTokens,
            @Param("completionTokens") Integer completionTokens,
            @Param("firstTokenMs") Integer firstTokenMs,
            @Param("durationMs") Integer durationMs,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 把 RUNNING 运行更新为 FAILED 或 TIMED_OUT。
     *
     * <p>terminalStatus 只能由 Service 从受控枚举中选择，
     * 不能直接接收前端字符串。</p>
     */
    @Update("""
        UPDATE agent_run
        SET status = #{terminalStatus},
            duration_ms = #{durationMs},
            error_code = #{errorCode},
            finished_at = #{finishedAt}
        WHERE id = #{runId}
          AND status = 'RUNNING'
        """)
    int failRun(
            @Param("runId") Long runId,
            @Param("terminalStatus")
            org.example.goshop.agent.entity.AgentRunStatus terminalStatus,
            @Param("durationMs") Integer durationMs,
            @Param("errorCode") String errorCode,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 查询当前消息之前最近完成的完整对话回合。
     *
     * <p>不能只从 agent_message 中查询 status = COMPLETED，
     * 因为失败运行的 USER 消息本身也是 COMPLETED，但没有成功的
     * ASSISTANT 消息与其组成有效回合。</p>
     *
     * <p>这里通过 agent_run 同时关联用户消息和助手消息，严格要求：</p>
     *
     * <ul>
     *     <li>AgentRun 为 COMPLETED；</li>
     *     <li>用户消息为 COMPLETED；</li>
     *     <li>助手消息为 COMPLETED；</li>
     *     <li>历史用户消息早于当前用户消息。</li>
     * </ul>
     *
     * <p>SQL 按时间倒序返回，Service 转换为模型消息前需要反转。</p>
     */
    @Select("""
        SELECT
            user_message.id AS userMessageId,
            user_message.content AS userContent,
            assistant_message.id AS assistantMessageId,
            assistant_message.content AS assistantContent
        FROM agent_run agent_run_record
        INNER JOIN agent_message user_message
            ON user_message.id = agent_run_record.user_message_id
           AND user_message.conversation_id =
               agent_run_record.conversation_id
           AND user_message.role = 'USER'
           AND user_message.status = 'COMPLETED'
        INNER JOIN agent_message assistant_message
            ON assistant_message.id =
               agent_run_record.assistant_message_id
           AND assistant_message.conversation_id =
               agent_run_record.conversation_id
           AND assistant_message.role = 'ASSISTANT'
           AND assistant_message.status = 'COMPLETED'
        WHERE agent_run_record.conversation_id =
              #{conversationId}
          AND agent_run_record.status = 'COMPLETED'
          AND user_message.id < #{currentUserMessageId}
        ORDER BY agent_run_record.created_at DESC,
                 agent_run_record.id DESC
        LIMIT #{limit}
        """)
    List<AgentHistoryTurn> selectRecentCompletedTurns(
            @Param("conversationId") Long conversationId,
            @Param("currentUserMessageId") Long currentUserMessageId,
            @Param("limit") int limit
    );

    /**
     * 删除指定会话下的全部模型运行记录。
     *
     * <p>调用前必须已经删除 agent_tool_call，否则数据库 RESTRICT
     * 外键会拒绝删除。运行中的会话不能调用该方法。</p>
     */
    @Delete("""
        DELETE FROM agent_run
        WHERE conversation_id = #{conversationId}
        """)
    int deleteByConversationId(
            @Param("conversationId") Long conversationId
    );
}
