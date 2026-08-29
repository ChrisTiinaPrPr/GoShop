package org.example.goshop.agent.tool.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.agent.service.AgentToolAuditService;
import org.example.goshop.agent.service.model.AgentToolCallHandle;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提供给模型调用的商城规则工具。
 *
 * <p>一次完整调用顺序为：</p>
 *
 * <ol>
 *     <li>恢复服务端可信上下文；</li>
 *     <li>创建 RUNNING 工具审计记录；</li>
 *     <li>发布 TOOL_STARTED；</li>
 *     <li>读取版本化商城规则；</li>
 *     <li>将审计记录收口为 SUCCEEDED 或 FAILED；</li>
 *     <li>发布 TOOL_COMPLETED；</li>
 *     <li>把结构化结果返回给模型。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class MallPolicyTool {

    private static final String TOOL_NAME =
            "get_mall_policy";

    private static final String STARTED_TEXT =
            "正在查询商城规则";

    private static final String SUCCEEDED_TEXT =
            "商城规则查询完成";

    private static final String FAILED_TEXT =
            "商城规则查询失败";

    private static final String FAILURE_CODE =
            "MALL_POLICY_QUERY_FAILED";

    private final MallPolicyCatalogService
            policyCatalogService;

    private final AgentToolAuditService
            toolAuditService;

    /**
     * 查询支付、发货或退款规则。
     */
    @Tool(
            name = TOOL_NAME,
            description = """
                    查询优购商城当前有效的支付、发货和退款规则。
                    当用户询问支付方式、订单过期、发货流程、
                    确认收货、退款条件或退款渠道限制时必须调用此工具。
                    PAYMENT 表示支付规则，SHIPPING 表示发货与收货规则，
                    REFUND 表示退款规则，ALL 表示同时查询全部规则。
                    不要根据常识猜测商城规则。
                    """
    )
    public MallPolicyResult getMallPolicy(
            @ToolParam(
                    description = """
                            要查询的规则主题，只能是
                            PAYMENT、SHIPPING、REFUND 或 ALL
                            """,
                    required = true
            )
            MallPolicyTopic topic,

            /*
             * 该参数由 Spring AI 在服务端注入。
             * 模型无法传入或修改 ToolContext。
             */
            ToolContext toolContext
    ) {
        AgentToolRequestContext requestContext =
                null;

        AgentToolCallHandle handle = null;

        /*
         * 用于区分：
         *
         * 1. 业务查询或失败审计前发生异常；
         * 2. 成功审计已经提交，但发送完成事件时发生异常。
         *
         * 已经写成 SUCCEEDED 的记录不能再错误地改成 FAILED。
         */
        boolean successAuditCompleted = false;

        try {
            Objects.requireNonNull(
                    topic,
                    "商城规则主题不能为空"
            );

            /*
             * 从 ToolContext 恢复 userId、conversationId、
             * runId 和当前运行专属的事件通道。
             */
            requestContext =
                    AgentToolRequestContext.from(
                            toolContext
                    );

            /*
             * 审计表只保存 topic，不保存完整用户问题、
             * 系统提示词或历史上下文。
             */
            handle = toolAuditService.start(
                    requestContext,
                    TOOL_NAME,
                    Map.of(
                            "topic",
                            topic.name()
                    )
            );

            /*
             * 必须使用审计 Service 返回的 toolCallId，
             * 确保数据库与 SSE 使用相同关联 ID。
             */
            requestContext.eventChannel()
                    .publishStarted(
                            handle.toolCallId(),
                            handle.toolName(),
                            STARTED_TEXT
                    );

            MallPolicyResult result =
                    policyCatalogService.getPolicy(
                            topic
                    );

            /*
             * 只保存结果摘要，不复制完整规则正文。
             */
            toolAuditService.completeSucceeded(
                    handle,
                    buildResultSummary(result)
            );

            successAuditCompleted = true;

            /*
             * 只有成功审计事务提交后，才向前端发送 success=true。
             */
            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            true,
                            SUCCEEDED_TEXT
                    );

            return result;
        } catch (RuntimeException exception) {
            /*
             * 如果成功审计尚未完成，就尝试把已创建的 RUNNING
             * 审计记录收口为 FAILED。
             */
            if (handle != null
                    && !successAuditCompleted) {
                safelyCompleteFailed(handle);
                safelyPublishFailed(
                        requestContext,
                        handle
                );
            }

            /*
             * 普通日志只记录安全标识和异常类型。
             *
             * 不记录 exception.getMessage()，因为底层异常可能包含
             * SQL、模型参数或其他内部实现信息。
             */
            log.warn(
                    "Agent 商城规则工具执行失败，"
                            + "runId={}, failureType={}",
                    requestContext == null
                            ? null
                            : requestContext.runId(),
                    exception.getClass()
                            .getSimpleName()
            );

            /*
             * 不把原异常作为 cause 交给模型。
             *
             * Spring AI 默认会把工具异常转换为模型可见文本，
             * 所以这里必须换成固定安全消息，防止模型读取并复述
             * 数据库异常或内部路径。
             */
            throw new BusinessException(
                    50301,
                    "商城规则查询暂时不可用"
            );
        }
    }

    /**
     * 构建允许写入审计表的规则结果摘要。
     *
     * <p>只保存版本、日期、主题和数量，不保存完整规则内容。</p>
     */
    private Map<String, ?> buildResultSummary(
            MallPolicyResult result
    ) {
        List<String> topics =
                result.policies()
                        .stream()
                        .map(policy ->
                                policy.topic().name()
                        )
                        .toList();

        return Map.of(
                "version",
                result.version(),
                "effectiveAt",
                result.effectiveAt().toString(),
                "policyCount",
                result.policies().size(),
                "topics",
                topics
        );
    }

    /**
     * 安全执行失败审计收口。
     *
     * <p>审计收口异常不能覆盖最初的工具异常，也不能把数据库
     * 异常详情写入普通日志。</p>
     */
    private void safelyCompleteFailed(
            AgentToolCallHandle handle
    ) {
        try {
            toolAuditService.completeFailed(
                    handle,
                    FAILURE_CODE
            );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 工具失败审计收口失败，"
                            + "runId={}, auditId={}",
                    handle.runId(),
                    handle.auditId()
            );
        }
    }

    /**
     * 安全发布 success=false 的工具完成事件。
     */
    private void safelyPublishFailed(
            AgentToolRequestContext requestContext,
            AgentToolCallHandle handle
    ) {
        if (requestContext == null) {
            return;
        }

        try {
            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            false,
                            FAILED_TEXT
                    );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 工具失败事件发布失败，"
                            + "runId={}, toolCallId={}",
                    handle.runId(),
                    handle.toolCallId()
            );
        }
    }
}