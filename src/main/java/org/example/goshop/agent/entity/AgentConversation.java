package org.example.goshop.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家购物 Agent 会话实体。
 *
 * <p>一个买家可以创建多个 Agent 会话，但只能访问自己的会话。
 * 所有查询、删除和发送消息操作都必须同时校验 id 和 userId，
 * 不能只根据前端传入的会话 ID 查询。</p>
 *
 * <p>lastMessageId 和 lastMessageAt 是为了加速会话列表查询而保存的
 * 冗余字段。更新它们时必须使用条件 SQL，只允许更大的消息 ID
 * 覆盖旧消息，避免并发请求导致会话摘要回退。</p>
 */
@Data
@TableName("agent_conversation")
public class AgentConversation {

    /**
     * 会话主键。
     *
     * <p>使用项目统一的 MyBatis-Plus 雪花 ID，
     * 数据库不使用自增主键。</p>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话所属买家，对应 sys_user.id。
     *
     * <p>该值只能来自已经通过 JWT 认证的 Authentication，
     * 禁止信任请求体中的用户 ID。</p>
     */
    private Long userId;

    /**
     * 会话标题。
     *
     * <p>新会话默认为“新会话”。保存第一条用户消息时，
     * 可以取消息前 30 个字符作为标题，不需要额外调用模型生成标题。</p>
     */
    private String title;

    /**
     * 会话最后一条可见消息 ID。
     *
     * <p>新建但尚未发送消息的会话为 null。</p>
     */
    private Long lastMessageId;

    /**
     * 最后一条可见消息完成时间。
     *
     * <p>与 lastMessageId 必须同时为空或同时有值。</p>
     */
    private LocalDateTime lastMessageAt;

    /** 会话创建时间，由数据库生成。 */
    private LocalDateTime createdAt;

    /** 会话最后更新时间，由数据库维护。 */
    private LocalDateTime updatedAt;
}
