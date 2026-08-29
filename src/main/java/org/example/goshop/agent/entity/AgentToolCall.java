package org.example.goshop.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 白名单工具调用审计记录。
 *
 * <p>模型本身不能访问数据库。模型只能提出工具调用请求，
 * 后端校验工具名和参数后，再调用商城中的 ProductService、
 * CartService、OrderService 等已有业务服务。</p>
 *
 * <p>该实体只用于审计和可观测性，不能成为商品、订单或购物车
 * 的事实来源。真实业务数据仍以原业务表为准。</p>
 */
@Data
@TableName("agent_tool_call")
public class AgentToolCall {

    /** 工具调用审计主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工具调用所属的 AgentRun。 */
    private Long runId;

    /**
     * 工具调用关联 ID。
     *
     * <p>首期由服务端生成 UUID，用于关联审计记录、
     * TOOL_STARTED 和 TOOL_COMPLETED 事件。</p>
     *
     * <p>未来若改为手动 Tool Calling 循环，可以直接保存
     * 供应商返回的 toolCallId，数据库结构无需变化。</p>
     */
    private String toolCallId;

    /**
     * 白名单工具名。
     *
     * <p>例如 search_products、get_product_detail。
     * 禁止保存任意类名或由模型动态指定要执行的方法。</p>
     */
    private String toolName;

    /**
     * 脱敏且截断后的参数摘要 JSON。
     *
     * <p>允许保存关键词、商品 ID、SKU ID 等非敏感参数。
     * 不能保存手机号、地址、API Key、系统提示词和完整订单。</p>
     *
     * <p>项目暂时使用 String 映射 MySQL JSON 字段，
     * 后续由 Jackson 统一序列化。</p>
     */
    private String argumentsSummaryJson;

    /**
     * 脱敏且截断后的结果摘要 JSON。
     *
     * <p>只保存调试需要的数量、资源 ID 和执行结果，
     * 不要把返回给模型的完整工具结果再次复制进审计表。</p>
     */
    private String resultSummaryJson;

    /** 工具执行状态。 */
    private AgentToolCallStatus status;

    /** 工具执行耗时，单位毫秒。 */
    private Integer durationMs;

    /** 工具调用创建时间。 */
    private LocalDateTime createdAt;

    /** 工具成功或失败的结束时间。 */
    private LocalDateTime finishedAt;
}
