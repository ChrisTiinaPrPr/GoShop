package org.example.goshop.agent.service.model;

import lombok.Data;

/**
 * 一个已经完整完成的历史对话回合。
 *
 * <p>只有 AgentRun 状态为 COMPLETED，且用户消息、助手消息都为
 * COMPLETED 时，数据库查询才会构造该对象。</p>
 *
 * <p>这是内部模型，不会直接返回给前端。</p>
 */
@Data
public class AgentHistoryTurn {

    /** 历史用户消息 ID。 */
    private Long userMessageId;

    /** 历史用户消息正文。 */
    private String userContent;

    /** 与用户消息对应的助手消息 ID。 */
    private Long assistantMessageId;

    /** 已经成功完成的助手回答。 */
    private String assistantContent;
}
