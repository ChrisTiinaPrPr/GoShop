package org.example.goshop.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.entity.AgentConversation;
import org.example.goshop.agent.entity.AgentRun;
import org.example.goshop.agent.entity.AgentRunStatus;
import org.example.goshop.agent.entity.AgentToolCall;
import org.example.goshop.agent.entity.AgentToolCallStatus;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.agent.service.model.AgentToolCallHandle;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 工具调用审计事务 Service。
 *
 * <p>每个开始、成功和失败操作都使用 REQUIRES_NEW 独立提交，
 * 避免模型流没有数据库事务时审计数据无法及时写入。</p>
 *
 * <p>该 Service 还负责：</p>
 *
 * <ul>
 *     <li>再次校验用户与会话归属；</li>
 *     <li>校验 AgentRun 仍处于 RUNNING；</li>
 *     <li>限制单次运行的工具调用总数；</li>
 *     <li>限制工具名称为服务端白名单；</li>
 *     <li>只保存长度受限的脱敏 JSON 摘要。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentToolAuditService {

    private static final int MAX_SUMMARY_JSON_CHARS = 2000;

    /**
     * 首期工具白名单。
     *
     * <p>后续增加商品、购物车和订单工具时，
     * 必须在代码评审后逐个加入，不能从配置或模型动态添加。</p>
     */
    private static final Set<String>
            ALLOWED_TOOL_NAMES = Set.of(
            "get_mall_policy",
            "search_products",
            "get_product_detail",
            "get_cart",
            "list_orders",
            "get_order_detail",
            "propose_add_cart_item"
    );

    /**
     * 失败摘要只允许稳定的内部错误分类。
     */
    private static final Pattern SAFE_ERROR_CODE =
            Pattern.compile("[A-Z0-9_]{1,50}");

    private final AgentToolCallMapper
            toolCallMapper;

    private final AgentRunMapper runMapper;

    private final AgentConversationMapper
            conversationMapper;

    private final AgentProperties agentProperties;

    private final ObjectMapper objectMapper;

    /**
     * 创建 RUNNING 工具审计记录。
     * 执行这个方法时，必须开启一个全新的数据库事务，若有正在运行的事务，需挂起。
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public AgentToolCallHandle start(
            AgentToolRequestContext context,
            String toolName,
            // 调用工具时用到的关键参数
            Map<String, ?> argumentsSummary
    ) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "工具请求上下文不能为空"
            );
        }

        String normalizedToolName =
                normalizeToolName(toolName);

        /*
         * 再次校验 conversationId 与 userId 的归属。
         *
         * 即使 ToolContext 来自服务端，也不能让工具层完全依赖
         * 上游代码正确，避免未来重构时形成越权漏洞。
         */
        AgentConversation conversation =
                conversationMapper
                        .selectOwnedConversation(
                                context.conversationId(),
                                context.userId()
                        );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "Agent 会话不存在或无权访问"
            );
        }

        /*
         * 锁定 AgentRun，使同一次运行的多个工具开始操作串行执行。
         * 这样“查询已调用数量 + 插入新记录”不会发生并发穿透。
         */
        AgentRun run =
                runMapper.selectByIdForUpdate(
                        context.runId()
                );

        if (run == null
                || !run.getConversationId().equals(
                context.conversationId()
        )) {
            throw new BusinessException(
                    40401,
                    "Agent 运行不存在"
            );
        }

        if (run.getStatus()
                != AgentRunStatus.RUNNING) {
            throw new BusinessException(
                    40901,
                    "Agent 运行已经结束"
            );
        }

        long existingCalls =
                toolCallMapper.countByRunId(
                        context.runId()
                );

        if (existingCalls
                >= agentProperties.maxToolCalls()) {
            throw new BusinessException(
                    40901,
                    "Agent 单次工具调用次数已达上限"
            );
        }

        String toolCallId =
                UUID.randomUUID().toString();

        AgentToolCall toolCall =
                new AgentToolCall();

        toolCall.setRunId(context.runId());
        toolCall.setToolCallId(toolCallId);
        toolCall.setToolName(normalizedToolName);

        /*
         * 调用方只能传入已经挑选过的安全摘要字段。
         * 当前规则工具只会传入 topic。
         */
        toolCall.setArgumentsSummaryJson(
                serializeSummary(argumentsSummary)
        );

        toolCall.setResultSummaryJson(null);
        toolCall.setStatus(
                AgentToolCallStatus.RUNNING
        );
        toolCall.setDurationMs(null);
        toolCall.setFinishedAt(null);

        if (toolCallMapper.insert(toolCall) != 1
                || toolCall.getId() == null) {
            throw new IllegalStateException(
                    "创建 Agent 工具审计记录失败"
            );
        }

        return new AgentToolCallHandle(
                toolCall.getId(),
                context.runId(),
                toolCallId,
                normalizedToolName,
                System.nanoTime()
        );
    }

    /**
     * 工具成功后的独立收口事务。
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void completeSucceeded(
            AgentToolCallHandle handle,
            Map<String, ?> resultSummary
    ) {
        requireHandle(handle);

        int affectedRows =
                toolCallMapper.completeSucceeded(
                        handle.auditId(),
                        handle.runId(),
                        serializeSummary(resultSummary),
                        handle.durationMs(),
                        LocalDateTime.now()
                );

        requireOneRow(
                affectedRows,
                "Agent 工具成功审计收口失败"
        );
    }

    /**
     * 工具失败后的独立收口事务。
     *
     * <p>不能传入 Throwable 或 throwable.getMessage()，
     * 只允许传入稳定的错误分类。</p>
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void completeFailed(
            AgentToolCallHandle handle,
            String safeErrorCode
    ) {
        requireHandle(handle);

        if (safeErrorCode == null
                || !SAFE_ERROR_CODE
                .matcher(safeErrorCode)
                .matches()) {
            throw new IllegalArgumentException(
                    "工具错误分类不合法"
            );
        }

        int affectedRows =
                toolCallMapper.completeFailed(
                        handle.auditId(),
                        handle.runId(),
                        serializeSummary(
                                Map.of(
                                        "errorCode",
                                        safeErrorCode
                                )
                        ),
                        handle.durationMs(),
                        LocalDateTime.now()
                );

        requireOneRow(
                affectedRows,
                "Agent 工具失败审计收口失败"
        );
    }

    /**
     * 工具名必须属于硬编码白名单。
     */
    private String normalizeToolName(
            String toolName
    ) {
        if (toolName == null
                || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "工具名不能为空"
            );
        }

        String normalized = toolName.strip();

        if (!ALLOWED_TOOL_NAMES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "工具不在服务端白名单中"
            );
        }

        return normalized;
    }

    /**
     * 把安全摘要序列化为合法 JSON。
     *
     * <p>不能直接截断 JSON 字符串，否则可能产生不合法 JSON，
     * 无法写入 MySQL JSON 字段。超过上限时改存固定降级摘要。</p>
     */
    private String serializeSummary(
            Map<String, ?> summary
    ) {
        Map<String, ?> safeSummary =
                summary == null
                        ? Map.of()
                        : Map.copyOf(summary);

        try {
            String json =
                    objectMapper.writeValueAsString(
                            safeSummary
                    );

            if (json.length()
                    <= MAX_SUMMARY_JSON_CHARS) {
                return json;
            }

            return """
                    {"truncated":true}
                    """.strip();
        } catch (JsonProcessingException exception) {
            /*
             * 审计表不能保存序列化异常正文。
             */
            return """
                    {"summaryUnavailable":true}
                    """.strip();
        }
    }

    private void requireHandle(
            AgentToolCallHandle handle
    ) {
        if (handle == null) {
            throw new IllegalArgumentException(
                    "工具审计句柄不能为空"
            );
        }
    }

    private void requireOneRow(
            int affectedRows,
            String message
    ) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

}
