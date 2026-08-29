package org.example.goshop.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.goshop.agent.entity.AgentConversation;

import java.time.LocalDateTime;

/**
 * Agent 会话数据访问层。
 *
 * <p>涉及用户提供的 conversationId 时，SQL 必须同时包含 user_id，
 * 不能先根据 ID 查询，再只依赖 Java 代码判断归属。</p>
 *
 * <p>这样既可以减少越权数据进入内存的机会，也能避免恶意用户
 * 使用其他人的会话 ID 锁住不属于自己的数据库记录。</p>
 */
@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    /**
     * 查询当前买家拥有的指定会话。
     *
     * <p>用于会话详情、历史消息查询前的所有权校验。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         JWT 中的当前买家 ID
     * @return 属于该用户的会话；不存在或不属于该用户时返回 null
     */
    @Select("""
            SELECT *
            FROM agent_conversation
            WHERE id = #{conversationId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    AgentConversation selectOwnedConversation(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );

    /**
     * 查询并锁定当前买家拥有的指定会话。
     *
     * <p>必须在 {@code @Transactional} 事务中调用。
     * 发送消息、更新最后消息或删除会话时使用。</p>
     *
     * <p>FOR UPDATE 会阻止其他事务同时修改同一会话，
     * 但不会锁定其他用户的会话。</p>
     */
    @Select("""
            SELECT *
            FROM agent_conversation
            WHERE id = #{conversationId}
              AND user_id = #{userId}
            FOR UPDATE
            """)
    AgentConversation selectOwnedConversationForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );

    /**
     * 第一次发送消息时初始化会话标题。
     *
     * <p>只允许默认标题“新会话”被替换。后续重试或并发请求
     * 不会反复覆盖标题。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         当前买家 ID
     * @param title          从第一条用户消息截取出的标题
     * @return 实际更新行数；1 表示成功，0 表示标题已经初始化
     */
    @Update("""
            UPDATE agent_conversation
            SET title = #{title}
            WHERE id = #{conversationId}
              AND user_id = #{userId}
              AND title = '新会话'
            """)
    int initializeTitle(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("title") String title
    );

    /**
     * 单调推进会话的最后消息。
     *
     * <p>只有新的 messageId 大于当前 last_message_id 时才更新。
     * 即使多个请求并发提交，较旧的请求也不能让会话摘要回退。</p>
     *
     * @param conversationId 会话 ID
     * @param messageId      新的最后消息 ID
     * @param messageAt      新消息完成时间
     * @return 实际更新行数
     */
    @Update("""
            UPDATE agent_conversation
            SET last_message_id = #{messageId},
                last_message_at = #{messageAt}
            WHERE id = #{conversationId}
              AND (
                  last_message_id IS NULL
                  OR last_message_id < #{messageId}
              )
            """)
    int advanceLastMessage(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId,
            @Param("messageAt") LocalDateTime messageAt
    );

    /**
     * 删除当前买家拥有的会话主记录。
     *
     * <p>即使 Service 已经完成归属校验，最终 DELETE 仍然带 user_id，
     * 防止未来代码调整后产生越权删除。</p>
     */
    @Delete("""
        DELETE FROM agent_conversation
        WHERE id = #{conversationId}
          AND user_id = #{userId}
        """)
    int deleteOwnedConversation(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );
}
