package org.example.goshop.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.agent.entity.AgentToolCall;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDateTime;

/**
 * Agent 工具调用审计数据访问层。
 *
 * <p>工具审计只记录脱敏摘要，不能保存完整订单、
 * 地址、手机号、系统提示词或工具完整返回值。</p>
 */
@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCall>{

    /**
     * 查询当前运行之前、同一会话中最近一次成功的指定工具调用。
     *
     * <p>该查询用于跨轮加购时恢复“用户刚才正在讨论哪个商品”。只读取
     * 服务端工具审计中的商品 ID，不读取或相信模型在助手正文里编造的 ID。
     * 当前运行仍必须重新执行搜索和详情工具，历史记录不能直接成为写操作
     * 的商品或 SKU 依据。</p>
     */
    @Select("""
            SELECT tool_call.*
            FROM agent_tool_call tool_call
            INNER JOIN agent_run run_record
                    ON run_record.id = tool_call.run_id
            WHERE run_record.conversation_id = #{conversationId}
              AND run_record.id <> #{currentRunId}
              AND run_record.status = 'COMPLETED'
              AND tool_call.tool_name = #{toolName}
              AND tool_call.status = 'SUCCEEDED'
            ORDER BY run_record.created_at DESC,
                     tool_call.created_at DESC,
                     tool_call.id DESC
            LIMIT 1
            """)
    AgentToolCall selectLatestSuccessfulBeforeRun(
            @Param("conversationId") Long conversationId,
            @Param("currentRunId") Long currentRunId,
            @Param("toolName") String toolName
    );

    /**
     * 查询当前运行中最近一次成功的指定工具调用。
     *
     * <p>加购提案工具使用该查询验证 productId 和 skuId 确实来自当前
     * AgentRun 的 get_product_detail 结果，而不是模型记忆、历史对话、
     * 数组序号或占位值。</p>
     */
    @Select("""
            SELECT *
            FROM agent_tool_call
            WHERE run_id = #{runId}
              AND tool_name = #{toolName}
              AND status = 'SUCCEEDED'
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    AgentToolCall selectLatestSuccessfulInRun(
            @Param("runId") Long runId,
            @Param("toolName") String toolName
    );

    /**
     * 查询一次 AgentRun 已创建的工具调用数量。
     *
     * <p>Service 会先锁定 agent_run 行，再执行该查询，
     * 从而串行控制同一次运行的工具调用上限。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM agent_tool_call
            WHERE run_id = #{runId}
            """)
    long countByRunId(
            @Param("runId") Long runId
    );

    /**
     * 把运行中的工具调用收口为成功。
     *
     * <p>WHERE status = 'RUNNING' 防止重复完成同一条审计记录。</p>
     */
    @Update("""
            UPDATE agent_tool_call
            SET status = 'SUCCEEDED',
                result_summary_json = #{resultSummaryJson},
                duration_ms = #{durationMs},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND run_id = #{runId}
              AND status = 'RUNNING'
            """)
    int completeSucceeded(
            @Param("id") Long id,
            @Param("runId") Long runId,
            @Param("resultSummaryJson")
            String resultSummaryJson,
            @Param("durationMs") Integer durationMs,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 把运行中的工具调用收口为失败。
     *
     * <p>resultSummaryJson 只能包含稳定的脱敏错误分类，
     * 不能保存 Throwable.message 或异常堆栈。</p>
     */
    @Update("""
            UPDATE agent_tool_call
            SET status = 'FAILED',
                result_summary_json = #{resultSummaryJson},
                duration_ms = #{durationMs},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND run_id = #{runId}
              AND status = 'RUNNING'
            """)
    int completeFailed(
            @Param("id") Long id,
            @Param("runId") Long runId,
            @Param("resultSummaryJson")
            String resultSummaryJson,
            @Param("durationMs") Integer durationMs,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * 删除指定会话下所有运行产生的工具调用审计。
     *
     * <p>agent_tool_call 没有 conversation_id，因此必须通过 agent_run
     * 关联到目标会话。该方法只能由会话删除事务调用，不能提供给模型工具。</p>
     *
     * @param conversationId 要删除的 Agent 会话 ID
     * @return 删除的工具调用数量；没有工具调用时返回 0 属于正常情况
     */
    @Delete("""
        DELETE tool_call
        FROM agent_tool_call tool_call
        INNER JOIN agent_run run_record
                ON run_record.id = tool_call.run_id
        WHERE run_record.conversation_id = #{conversationId}
        """)
    int deleteByConversationId(
            @Param("conversationId") Long conversationId
    );
}
