package org.example.goshop.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.dto.AgentConversationResponse;
import org.example.goshop.agent.dto.AgentMessageCursorQuery;
import org.example.goshop.agent.dto.AgentMessagePageResponse;
import org.example.goshop.agent.service.AgentConversationService;
import org.example.goshop.common.api.Result;
import org.example.goshop.product.dto.PageResult;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.example.goshop.agent.dto.AgentSendMessageRequest;
import org.example.goshop.agent.dto.AgentSseEvent;
import org.example.goshop.agent.service.AgentRunOrchestrationService;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.goshop.agent.dto.AgentActionConfirmResponse;
import org.example.goshop.agent.dto.AgentActionStatusResponse;
import org.example.goshop.agent.service.AgentActionService;

/**
 * 买家购物 Agent 会话接口。
 *
 * <p>当前阶段只提供以下数据库能力：</p>
 *
 * <ul>
 *     <li>创建 Agent 会话；</li>
 *     <li>分页查询当前买家的会话；</li>
 *     <li>使用消息 ID 游标查询会话历史。</li>
 * </ul>
 *
 * <p>这一阶段还不会调用大模型，也不会执行商品搜索、
 * 订单查询或加入购物车等 Agent 工具。</p>
 *
 * <p>安全要求：userId 只能从经过 JWT 校验的 Authentication
 * 中获取，绝不能让前端通过请求参数传入 userId。</p>
 */
@Tag(name = "买家购物 Agent")
@RestController
@RequestMapping("/api/v1/buyer/agent")
@RequiredArgsConstructor
@Validated
public class BuyerAgentController {

    private final AgentConversationService conversationService;
    /**
     * 使用 ObjectProvider 而不是直接注入编排 Service。
     *
     * <p>当 AGENT_ENABLED=false 或 AI_MODEL_PROVIDER=none 时，
     * AgentRunOrchestrationService 不会被创建，但原商城和 Agent 历史接口
     * 仍然必须能够正常启动。</p>
     */
    private final ObjectProvider<AgentRunOrchestrationService> orchestrationServiceProvider;

    /**
     * AgentActionService 只有在 AGENT_ENABLED=true 时才创建。
     *
     * <p>使用 ObjectProvider 可以保证 Agent 关闭时商城仍能正常启动，
     * 动作接口则统一返回 50301。</p>
     */
    private final ObjectProvider<AgentActionService> actionServiceProvider;

    /**
     * 创建一个新的空 Agent 会话。
     *
     * <p>请求不需要携带请求体。会话所有者取自当前登录用户，
     * 默认标题由 Service 统一生成。</p>
     */
    @PostMapping("/conversations")
    @Operation(summary = "创建 Agent 会话")
    public Result<AgentConversationResponse> createConversation(
            Authentication authentication
    ) {
        /*
         * JwtAuthenticationFilter 已经把当前用户 ID 写入 principal。
         *
         * 不从请求体、请求头或查询参数读取 userId，
         * 防止用户伪造其他买家的身份。
         */
        Long buyerUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                conversationService.createConversation(buyerUserId)
        );
    }

    /**
     * 分页查询当前买家的 Agent 会话。
     *
     * <p>Service 查询条件会强制包含当前 userId，
     * 因此不能读取其他买家的会话。</p>
     */
    @GetMapping("/conversations")
    @Operation(summary = "分页查询自己的 Agent 会话")
    public Result<PageResult<AgentConversationResponse>> conversations(
            Authentication authentication,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long pageSize
    ) {
        Long buyerUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                conversationService.listConversations(
                        buyerUserId,
                        page,
                        pageSize
                )
        );
    }

    /**
     * 使用消息 ID 游标查询指定会话的历史消息。
     *
     * <p>首次加载时不传 beforeMessageId；向上加载更早消息时，
     * 将当前页面的 oldestMessageId 作为 beforeMessageId 传入。</p>
     *
     * <p>例如：</p>
     *
     * <pre>
     * GET /api/v1/buyer/agent/conversations/100/messages?limit=30
     *
     * GET /api/v1/buyer/agent/conversations/100/messages
     *     ?beforeMessageId=2041290571319791616&amp;limit=30
     * </pre>
     */
    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "查询 Agent 会话历史消息")
    public Result<AgentMessagePageResponse> messages(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            /*
             * 使用 @ModelAttribute 接收 GET 查询参数。
             *
             * Spring 会把 beforeMessageId 和 limit 自动绑定到
             * AgentMessageCursorQuery record 中。
             *
             * @Valid 会触发 DTO 上的 @Positive、@Min 和 @Max 校验。
             */
            @Valid
            @ModelAttribute
            AgentMessageCursorQuery query
    ) {
        Long buyerUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                conversationService.listMessages(
                        buyerUserId,
                        conversationId,
                        query
                )
        );
    }

    /**
     * 向购物 Agent 发送消息，并使用 SSE 返回模型流。
     *
     * <p>该接口不能使用统一 Result 包装，因为响应体不是一次性 JSON，
     * 而是由多个 AgentSseEvent 组成的持续事件流。</p>
     *
     * <p>前端需要使用 fetch + ReadableStream 发起 POST 请求。
     * 浏览器原生 EventSource 只支持 GET，不适合本接口。</p>
     */
    @PostMapping(
            value = "/conversations/{conversationId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(
            summary = "向购物 Agent 发送消息",
            description = "使用 text/event-stream 流式返回模型文本和运行状态"
    )
    public Flux<AgentSseEvent<?>> sendMessage(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            @Valid
            @RequestBody
            AgentSendMessageRequest request
    ) {
        Long buyerUserId =
                (Long) authentication.getPrincipal();

        /*
         * 模型未配置或 Agent 关闭时，ObjectProvider 返回 null。
         *
         * 不允许因为模型 Bean 不存在而抛出 NoSuchBeanDefinitionException，
         * 应返回开发文档约定的 50301。
         */
        AgentRunOrchestrationService orchestrationService =
                orchestrationServiceProvider
                        .getIfAvailable();

        if (orchestrationService == null) {
            throw new BusinessException(
                    50301,
                    "购物助手暂未启用或模型服务未配置"
            );
        }

        /*
         * stream() 内会：
         *
         * 1. 从 JWT userId 校验会话归属；
         * 2. 根据 clientMessageId 初始化或复用运行；
         * 3. 返回共享 SSE 流；
         * 4. 最终完成数据库成功或失败收口。
         */
        return orchestrationService.stream(
                buyerUserId,
                conversationId,
                request
        );
    }

    /**
     * 确认执行一个 Agent 加购动作。
     *
     * <p>请求不能携带请求体。SKU 和数量必须从服务端保存的
     * agent_action.payload_json 中读取。</p>
     */
    @PostMapping(
            "/actions/{actionId}/confirm"
    )
    @Operation(
            summary = "确认 Agent 加购动作",
            description = """
                根据服务端保存的待确认动作加入购物车。
                请求不能重新提交或修改 SKU 和数量。
                相同 Idempotency-Key 重复请求返回第一次结果。
                """
    )
    public Result<AgentActionConfirmResponse>
    confirmAction(
            Authentication authentication,

            @PathVariable
            @Positive(message = "动作 ID 必须是正数")
            Long actionId,

            @RequestHeader("Idempotency-Key")
            @NotBlank(
                    message =
                            "Idempotency-Key 不能为空"
            )
            @Size(
                    max = 64,
                    message =
                            "Idempotency-Key 长度不能超过 64 个字符"
            )
            @Pattern(
                    regexp = "^[A-Za-z0-9_-]+$",
                    message =
                            "Idempotency-Key 只能包含字母、数字、下划线和短横线"
            )
            String idempotencyKey
    ) {
        /*
         * userId 只能来自经过 JWT 校验的 Authentication。
         *
         * 请求体、查询参数和 Header 中都不能接收 userId。
         */
        Long buyerUserId =
                (Long) authentication.getPrincipal();

        AgentActionService actionService =
                requireActionService();

        return Result.ok(
                actionService.confirmAction(
                        buyerUserId,
                        actionId,
                        idempotencyKey
                )
        );
    }

    /**
     * 取消一个尚未确认的 Agent 动作。
     *
     * <p>接口不需要请求体，也不需要 Idempotency-Key。
     * 重复取消已经 CANCELLED 的动作会返回当前状态。</p>
     */
    @PostMapping(
            "/actions/{actionId}/cancel"
    )
    @Operation(
            summary = "取消 Agent 待确认动作",
            description = """
                取消当前买家自己的 PENDING 动作。
                已确认或已过期的动作不能取消。
                """
    )
    public Result<AgentActionStatusResponse>
    cancelAction(
            Authentication authentication,

            @PathVariable
            @Positive(message = "动作 ID 必须是正数")
            Long actionId
    ) {
        Long buyerUserId =
                (Long) authentication.getPrincipal();

        AgentActionService actionService =
                requireActionService();

        return Result.ok(
                actionService.cancelAction(
                        buyerUserId,
                        actionId
                )
        );
    }

    /**
     * 获取启用状态下的动作 Service。
     */
    private AgentActionService requireActionService() {
        AgentActionService actionService =
                actionServiceProvider
                        .getIfAvailable();

        if (actionService == null) {
            throw new BusinessException(
                    50301,
                    "购物助手暂未启用"
            );
        }

        return actionService;
    }

    /**
     * 删除当前买家自己的 Agent 会话。
     *
     * <p>删除的是会话、消息、运行、工具审计和待确认动作。
     * 已经确认加入购物车的商品不会因此从购物车移除，因为购物车是独立
     * 业务事实，不属于 Agent 会话历史。</p>
     */
    @DeleteMapping("/conversations/{conversationId}")
    @Operation(
            summary = "删除自己的 Agent 会话",
            description = """
                硬删除会话及其消息、运行、工具调用和动作记录。
                会话正在生成回复时返回 40901。
                """
    )
    public Result<Void> deleteConversation(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId
    ) {
        Long buyerUserId =
                (Long) authentication.getPrincipal();

        conversationService.deleteConversation(
                buyerUserId,
                conversationId
        );

        return Result.ok();
    }
}
