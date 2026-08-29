package org.example.goshop.agent.entity;

/**
 * Agent 可见消息的发送角色。
 *
 * <p>这里只保留最终展示给用户的两种角色：</p>
 *
 * <ul>
 *     <li>USER：买家发送的消息；</li>
 *     <li>ASSISTANT：AI 购物助手生成的消息。</li>
 * </ul>
 *
 * <p>工具调用过程不会作为普通聊天消息保存，而是单独写入
 * agent_tool_call 表。因此这里不需要 TOOL、SYSTEM 等角色。</p>
 *
 * <p>MyBatis-Plus 默认使用枚举常量名称写入数据库，
 * 即 USER 和 ASSISTANT。不要使用 ordinal 数字保存枚举，
 * 否则调整枚举顺序会破坏历史数据。</p>
 */
public enum AgentMessageRole {

    /** 买家发送的自然语言消息。 */
    USER,

    /** AI 购物助手生成的可见消息。 */
    ASSISTANT

}
