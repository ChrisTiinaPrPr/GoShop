package org.example.goshop.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家与商家之间的一对一聊天会话。
 *
 * <p>一个 buyerUserId + merchantId 组合在数据库中只能有一条记录。
 * 最近消息和已读游标不能直接使用 updateById 随意覆盖，
 * 后续必须通过 Mapper 条件更新保证游标只能向前推进。</p>
 */
@Data
@TableName("chat_conversation")
public class ChatConversation {

    /**
     * 会话主键。
     *
     * <p>使用项目统一的 MyBatis-Plus 雪花 ID，
     * 数据库不使用 AUTO_INCREMENT。</p>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 买家对应的 sys_user.id。 */
    private Long buyerUserId;

    /** 会话对应的 merchant.id。 */
    private Long merchantId;

    /**
     * 当前会话最后一条消息 ID。
     *
     * <p>新建但尚未发送消息的会话为 null。</p>
     */
    private Long lastMessageId;

    /** 最后一条消息的服务端创建时间。 */
    private LocalDateTime lastMessageAt;

    /**
     * 买家最后读到的消息 ID。
     *
     * <p>null 表示买家尚未提交过已读位置。</p>
     */
    private Long buyerLastReadMessageId;

    /**
     * 商家最后读到的消息 ID。
     *
     * <p>null 表示商家尚未提交过已读位置。</p>
     */
    private Long merchantLastReadMessageId;

    /** 会话创建时间，由数据库生成。 */
    private LocalDateTime createdAt;

    /** 会话最后更新时间，由数据库维护。 */
    private LocalDateTime updatedAt;
}
