package org.example.goshop.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次完整的 Agent 模型运行。
 *
 * <p>一条 USER 消息对应一条 AgentRun，一条 AgentRun 对应一条
 * ASSISTANT 占位消息。即使模型失败，也保留运行记录和失败状态，
 * 便于排查稳定性、延迟和模型费用。</p>
 *
 * <p>数据库中还有 active_conversation_id 生成列，用于保证同一个会话
 * 只能有一个 RUNNING 运行。该列完全由 MySQL 计算，不在 Java Entity
 * 中声明，避免 MyBatis 在插入或更新时尝试修改生成列。</p>
 */
@Data
@TableName("agent_run")
public class AgentRun {

    /** 运行主键，由 MyBatis-Plus 生成雪花 ID。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 本次运行所属的 Agent 会话。 */
    private Long conversationId;

    /**
     * 触发本次运行的用户消息 ID。
     *
     * <p>数据库对该字段建立唯一索引，同一条用户消息不能创建两次运行。</p>
     */
    private Long userMessageId;

    /**
     * 本次运行生成的助手占位消息 ID。
     *
     * <p>数据库对该字段也建立唯一索引，一条助手消息不能被多个运行复用。</p>
     */
    private Long assistantMessageId;

    /**
     * 模型供应商标识。
     *
     * <p>示例：openai-compatible。不要在这里保存 Base URL 或 API Key。</p>
     */
    private String provider;

    /**
     * 本次运行实际使用的模型名。
     *
     * <p>必须保存实际值，而不是只依赖当前配置。以后切换模型后，
     * 才能知道历史运行由哪个模型生成。</p>
     */
    private String model;

    /** 当前运行状态。 */
    private AgentRunStatus status;

    /**
     * 模型输入 Token 数。
     *
     * <p>供应商没有返回用量时可以为空。</p>
     */
    private Integer promptTokens;

    /** 模型输出 Token 数，供应商没有返回时可以为空。 */
    private Integer completionTokens;

    /**
     * 从开始运行到收到第一个文本增量的时间，单位毫秒。
     *
     * <p>该指标主要用于评估用户感知到的响应速度。</p>
     */
    private Integer firstTokenMs;

    /** 本次运行总耗时，单位毫秒。 */
    private Integer durationMs;

    /**
     * 脱敏后的错误分类。
     *
     * <p>例如 MODEL_TIMEOUT、MODEL_RATE_LIMIT、TOOL_FAILED。
     * 不能保存完整异常堆栈、API Key 或供应商原始响应。</p>
     */
    private String errorCode;

    /** 运行创建时间，由数据库生成。 */
    private LocalDateTime createdAt;

    /**
     * 运行完成、失败或超时时间。
     *
     * <p>RUNNING 状态时必须为空。</p>
     */
    private LocalDateTime finishedAt;
}
