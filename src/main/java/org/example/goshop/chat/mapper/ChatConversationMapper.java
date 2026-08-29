package org.example.goshop.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.chat.entity.ChatConversation;

import java.time.LocalDateTime;

/**
 * 聊天会话数据访问层。
 *
 * <p>复杂的最近消息更新、已读游标更新后续使用条件 SQL 实现，
 * 不能直接使用 updateById 覆盖游标。</p>
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    /**
     * 根据买家和商家查询唯一会话。
     */
    @Select("""
        SELECT *
        FROM chat_conversation
        WHERE buyer_user_id = #{buyerUserId}
          AND merchant_id = #{merchantId}
        LIMIT 1
        """)
    ChatConversation selectByBuyerAndMerchant(
            @Param("buyerUserId") Long buyerUserId,
            @Param("merchantId") Long merchantId
    );

    /**
     * 对会话行加排他锁。
     *
     * <p>发送消息、更新最近消息或推进已读游标时使用，
     * 必须在 @Transactional 事务中调用。</p>
     */
    @Select("""
        SELECT *
        FROM chat_conversation
        WHERE id = #{conversationId}
        FOR UPDATE
        """)
    ChatConversation selectByIdForUpdate(
            @Param("conversationId") Long conversationId
    );

    /**
     * 买家发送消息时锁定自己参与的会话。
     *
     * <p>把 buyerUserId 放进 SQL，而不是先锁定再在 Java 中判断权限，
     * 可以防止恶意用户使用其他会话 ID 锁住不属于自己的会话。</p>
     */
    @Select("""
    SELECT *
    FROM chat_conversation
    WHERE id = #{conversationId}
      AND buyer_user_id = #{buyerUserId}
    FOR UPDATE
    """)
    ChatConversation selectBuyerConversationForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("buyerUserId") Long buyerUserId
    );

    /**
     * 商家发送消息时锁定属于当前店铺的会话。
     */
    @Select("""
    SELECT *
    FROM chat_conversation
    WHERE id = #{conversationId}
      AND merchant_id = #{merchantId}
    FOR UPDATE
    """)
    ChatConversation selectMerchantConversationForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("merchantId") Long merchantId
    );

    /**
     * 条件更新会话的最后消息。
     *
     * <p>即使以后部署多个应用实例，也只允许更大的消息 ID
     * 覆盖较小的 last_message_id，防止并发事务把会话摘要回退。</p>
     */
    @Update("""
    UPDATE chat_conversation
    SET last_message_id = #{messageId},
        last_message_at = #{messageCreatedAt}
    WHERE id = #{conversationId}
      AND (
          last_message_id IS NULL
          OR last_message_id < #{messageId}
      )
    """)
    int advanceLastMessage(
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId,
            @Param("messageCreatedAt") LocalDateTime messageCreatedAt
    );

    /**
     * 单调推进买家已读游标。
     *
     * <p>只有新消息 ID 大于原游标时才更新，旧请求和重复请求不会导致游标回退。</p>
     */
    @Update("""
    UPDATE chat_conversation
    SET buyer_last_read_message_id = #{messageId}
    WHERE id = #{conversationId}
      AND buyer_user_id = #{buyerUserId}
      AND (
          buyer_last_read_message_id IS NULL
          OR buyer_last_read_message_id < #{messageId}
      )
    """)
    int advanceBuyerReadCursor(
            @Param("conversationId") Long conversationId,
            @Param("buyerUserId") Long buyerUserId,
            @Param("messageId") Long messageId
    );

    /**
     * 单调推进商家已读游标。
     */
    @Update("""
    UPDATE chat_conversation
    SET merchant_last_read_message_id = #{messageId}
    WHERE id = #{conversationId}
      AND merchant_id = #{merchantId}
      AND (
          merchant_last_read_message_id IS NULL
          OR merchant_last_read_message_id < #{messageId}
      )
    """)
    int advanceMerchantReadCursor(
            @Param("conversationId") Long conversationId,
            @Param("merchantId") Long merchantId,
            @Param("messageId") Long messageId
    );


}
