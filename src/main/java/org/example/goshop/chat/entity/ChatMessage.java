package org.example.goshop.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.example.goshop.chat.dto.ChatMessageType;

import java.time.LocalDateTime;

/**
 * 聊天消息持久化实体。
 *
 * <p>MySQL 是聊天消息的最终事实来源。WebSocket 只推送消息事件，
 * 不代替数据库保存聊天记录。</p>
 *
 * <p>三种消息的载荷规则：</p>
 * <ul>
 *     <li>TEXT：textContent 不为空；</li>
 *     <li>IMAGE：imageObjectKey、imageMetaJson 不为空；</li>
 *     <li>ORDER：orderId 不为空。</li>
 * </ul>
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /**
     * 消息 ID，同时也是历史查询游标。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 消息所属会话 ID。 */
    private Long conversationId;

    /**
     * 发送者的 sys_user.id。
     *
     * <p>商家发送消息时，这里保存商家账号的 userId，
     * 不是 merchant.id。</p>
     */
    private Long senderUserId;

    /**
     * 发送门户，只能是 USER 或 MERCHANT。
     */
    private String senderRole;

    /**
     * 消息类型。
     *
     * <p>MyBatis 默认按枚举名称保存，即 TEXT、IMAGE、ORDER，
     * 禁止使用 ordinal 数字持久化。</p>
     */
    private ChatMessageType messageType;

    /** TEXT 消息正文，其他类型必须为空。 */
    private String textContent;

    /**
     * IMAGE 消息的私有 OSS 对象 Key。
     *
     * <p>这里只保存 objectKey，不能保存短期签名 URL。</p>
     */
    private String imageObjectKey;

    /**
     * 图片元数据 JSON。
     *
     * <p>保存 MIME、实际字节数、宽度和高度等信息。</p>
     */
    private String imageMetaJson;

    /**
     * ORDER 消息关联的 mall_order.id。
     *
     * <p>接口只接收 orderNo，Service 校验订单归属后转换为 orderId。</p>
     */
    private Long orderId;

    /**
     * 客户端生成的 UUID 幂等键。
     *
     * <p>网络重试必须复用相同 clientMessageId。</p>
     */
    private String clientMessageId;

    /** 消息落库时间，由数据库生成。 */
    private LocalDateTime createdAt;
}
