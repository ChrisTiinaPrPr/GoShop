package org.example.goshop.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.agent.entity.AgentAction;

/**
 * Agent 待确认动作数据访问层。
 *
 * <p>该 Mapper 只由 Agent 动作 Service 使用，
 * 不能直接暴露给模型工具或 Controller。</p>
 */
@Mapper
public interface AgentActionMapper extends BaseMapper<AgentAction>{

    /**
     * 确认或取消动作前锁定动作记录。
     *
     * <p>必须在带事务的 Service 方法中调用。FOR UPDATE 可以保证：</p>
     *
     * <ul>
     *     <li>两个并发确认请求不能同时执行加购；</li>
     *     <li>确认和取消不能同时成功；</li>
     *     <li>动作状态只能从 PENDING 进入一个终态。</li>
     * </ul>
     *
     * <p>这里只根据 actionId 查询。Service 取得记录后必须再次比较
     * action.userId 与当前 JWT userId，不能因为记录存在就允许执行。</p>
     */
    @Select("""
            SELECT *
            FROM agent_action
            WHERE id = #{actionId}
            LIMIT 1
            FOR UPDATE
            """)
    AgentAction selectByIdForUpdate(
            @Param("actionId") Long actionId
    );

    /**
     * 删除会话时先删除该会话产生的动作。
     *
     * <p>agent_action 对 agent_conversation 存在外键，
     * 所以删除会话时必须先删除动作记录。</p>
     */
    @Delete("""
            DELETE FROM agent_action
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(
            @Param("conversationId")
            Long conversationId
    );

    /**
     * 查询当前用户是否已经使用某个确认幂等键。
     *
     * <p>用于在调用 CartService 前尽早拒绝将同一个 Key
     * 用于两个不同的 Agent 动作。</p>
     */
    @Select("""
        SELECT *
        FROM agent_action
        WHERE user_id = #{userId}
          AND idempotency_key = #{idempotencyKey}
        LIMIT 1
        """)
    AgentAction selectByUserIdAndIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey")
            String idempotencyKey
    );
}
