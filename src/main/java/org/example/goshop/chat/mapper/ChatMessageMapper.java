package org.example.goshop.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.chat.entity.ChatMessage;

import java.util.List;

/**
 * 聊天消息数据访问层。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 根据完整发送身份和客户端 UUID 查询已保存消息。
     *
     * <p>发送请求超时重试时，如果已经存在记录，
     * Service 应直接返回该消息，而不是再次插入。</p>
     */
    @Select("""
        SELECT *
        FROM chat_message
        WHERE sender_user_id = #{senderUserId}
          AND sender_role = #{senderRole}
          AND client_message_id = #{clientMessageId}
        LIMIT 1
        """)
    ChatMessage selectByIdempotencyKey(
            @Param("senderUserId") Long senderUserId,
            @Param("senderRole") String senderRole,
            @Param("clientMessageId") String clientMessageId
    );

    /**
     * 查询某条消息是否确实属于指定会话。
     *
     * <p>更新已读游标前必须校验，防止用户拿其他会话的消息 ID
     * 推进当前会话游标。</p>
     */
    @Select("""
        SELECT *
        FROM chat_message
        WHERE id = #{messageId}
          AND conversation_id = #{conversationId}
        LIMIT 1
        """)
    ChatMessage selectInConversation(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId
    );

    /**
     * 首次打开会话时查询最近一批消息。
     *
     * <p>SQL 先按 ID 倒序查询，Service 最终会反转成升序返回。</p>
     */
    @Select("""
    SELECT *
    FROM chat_message
    WHERE conversation_id = #{conversationId}
    ORDER BY id DESC
    LIMIT #{limit}
    """)
    List<ChatMessage> selectLatestMessages(
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );

    /**
     * 查询指定消息 ID 之前的历史消息。
     */
    @Select("""
    SELECT *
    FROM chat_message
    WHERE conversation_id = #{conversationId}
      AND id < #{beforeMessageId}
    ORDER BY id DESC
    LIMIT #{limit}
    """)
    List<ChatMessage> selectBeforeMessage(
            @Param("conversationId") Long conversationId,
            @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit
    );

    /**
     * 查询指定消息 ID 之后的新消息，用于 WebSocket 断线补偿。
     *
     * <p>该查询直接按 ID 升序返回。</p>
     */
    @Select("""
    SELECT *
    FROM chat_message
    WHERE conversation_id = #{conversationId}
      AND id > #{afterMessageId}
    ORDER BY id ASC
    LIMIT #{limit}
    """)
    List<ChatMessage> selectAfterMessage(
            @Param("conversationId") Long conversationId,
            @Param("afterMessageId") Long afterMessageId,
            @Param("limit") int limit
    );

    /**
     * 统计某一方尚未读取的对方消息数量。
     *
     * <p>只统计 peerRole 发送的消息，不统计当前用户自己发送的消息。</p>
     *
     * @param conversationId   会话 ID
     * @param peerRole         对方角色；买家查询时传 MERCHANT，商家查询时传 USER
     * @param lastReadMessageId 当前端最后已读消息 ID；null 表示从未读过
     */
    @Select("""
    SELECT COUNT(*)
    FROM chat_message
    WHERE conversation_id = #{conversationId}
      AND sender_role = #{peerRole}
      AND (
          #{lastReadMessageId} IS NULL
          OR id > #{lastReadMessageId}
      )
    """)
    long countUnreadMessages(
            @Param("conversationId") Long conversationId,
            @Param("peerRole") String peerRole,
            @Param("lastReadMessageId") Long lastReadMessageId
    );
}
