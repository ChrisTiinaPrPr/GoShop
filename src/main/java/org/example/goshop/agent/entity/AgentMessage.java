package org.example.goshop.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家与购物 Agent 之间的可见消息。
 *
 * <p>该表是 Agent 页面展示历史的事实来源，只保存用户最终看到的消息。
 * 模型工具的请求参数、工具结果和中间推理过程不能写入这里。</p>
 *
 * <p>典型消息写入流程：</p>
 *
 * <ol>
 *     <li>保存 USER + COMPLETED 用户消息；</li>
 *     <li>创建 ASSISTANT + STREAMING 助手占位消息；</li>
 *     <li>通过 SSE 把模型文本增量发送给前端；</li>
 *     <li>模型完成后，把完整正文和状态更新为 COMPLETED；</li>
 *     <li>模型失败时，把状态更新为 FAILED。</li>
 * </ol>
 */
@Data
@TableName("agent_message")
public class AgentMessage {

    /**
     * 消息主键，同时也是历史分页游标。
     *
     * <p>使用雪花 ID 后，后续可以通过 id 小于 beforeMessageId
     * 查询更早的历史消息。</p>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 消息所属 Agent 会话 ID。 */
    private Long conversationId;

    /**
     * 消息角色，只允许 USER 或 ASSISTANT。
     *
     * <p>MyBatis-Plus 默认按枚举名称写入数据库。</p>
     */
    private AgentMessageRole role;

    /**
     * 消息纯文本正文。
     *
     * <p>前端展示时必须按纯文本转义，不能把模型输出直接当作 HTML。
     * ASSISTANT + STREAMING 状态下允许暂时保存空字符串。</p>
     */
    private String content;

    /**
     * 消息状态。
     *
     * <p>只有 ASSISTANT 消息会经历 STREAMING 和 FAILED；
     * USER 消息创建后必须直接是 COMPLETED。</p>
     */
    private AgentMessageStatus status;

    /**
     * 浏览器生成的用户消息幂等 UUID。
     *
     * <p>USER 消息必须有值，ASSISTANT 消息必须为空。
     * 网络重试时，浏览器必须复用原来的 clientMessageId。</p>
     */
    private String clientMessageId;

    /**
     * 生成该助手消息的 Agent 运行 ID。
     *
     * <p>USER 消息通常为空。ASSISTANT 消息在创建 AgentRun 后写入 runId。
     * 数据库没有为该字段创建外键，是为了避免 agent_message 和
     * agent_run 之间产生循环外键。</p>
     */
    private Long runId;

    /**
     * 助手消息对应的安全结构化结果卡片 JSON 数组。
     *
     * <p>这里只保存服务端从白名单商品/订单工具映射出的展示快照，
     * 不保存模型原始响应、工具参数、地址、手机号或完整工具结果。</p>
     */
    private String resultCardsJson;

    /** 消息创建时间，由数据库生成。 */
    private LocalDateTime createdAt;

    /**
     * 助手消息完成或失败时间。
     *
     * <p>STREAMING 状态为空；COMPLETED 或 FAILED 时写入当前时间。
     * USER 消息可以在创建时直接写入当前时间。</p>
     */
    private LocalDateTime completedAt;
}
