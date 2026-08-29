package org.example.goshop.agent.service;

import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.service.model.AgentModelStreamChunk;
import org.example.goshop.agent.service.model.AgentModelIdentity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.agent.tool.policy.MallPolicyTool;
import org.example.goshop.agent.tool.product.ProductSearchTool;
import org.example.goshop.agent.tool.product.ProductDetailTool;
import org.example.goshop.agent.tool.cart.CartTool;
import org.example.goshop.agent.tool.order.ListOrdersTool;
import org.example.goshop.agent.tool.order.GetOrderDetailTool;

import java.util.Objects;
import java.time.Duration;
import java.util.List;

/**
 * Spring AI ChatClient 的流式调用适配器。
 *
 * <p>该类只负责：</p>
 *
 * <ul>
 *     <li>接收已经构建好的 Spring AI 消息；</li>
 *     <li>调用 ChatClient；</li>
 *     <li>返回模型文本增量和供应商 Token Usage；</li>
 *     <li>限制模型流的总执行时间。</li>
 * </ul>
 *
 * <p>该类不负责数据库事务、SSE 包装、工具执行和异常提示落库。
 * 这些职责由后续运行编排 Service 处理。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "spring.ai.model",
        name = "chat",
        havingValue = "openai"
)
public class AgentModelStreamingClient {

    private final ChatClient shoppingAgentChatClient;
    private final AgentProperties agentProperties;
    private final String modelName;
    private final MallPolicyTool mallPolicyTool;
    private final ProductSearchTool productSearchTool;
    private final ProductDetailTool productDetailTool;
    private final CartTool cartTool;
    private final ListOrdersTool listOrdersTool;
    private final GetOrderDetailTool getOrderDetailTool;


    /**
     * 显式构造器便于给 ChatClient 添加 Bean 名限定。
     *
     * <p>以后项目可能同时存在客服 Agent、商家 Agent 等多个 ChatClient，
     * 使用 Qualifier 可以避免注入错误的模型客户端。</p>
     */
    public AgentModelStreamingClient(
            @Qualifier("shoppingAgentChatClient")
            ChatClient shoppingAgentChatClient,

            AgentProperties agentProperties,

            MallPolicyTool mallPolicyTool,

            ProductSearchTool productSearchTool,

            ProductDetailTool productDetailTool,

            CartTool cartTool,

            ListOrdersTool listOrdersTool,

            GetOrderDetailTool getOrderDetailTool,

            @Value("${spring.ai.openai.chat.model}")
            String modelName
    ) {
        this.shoppingAgentChatClient =
                shoppingAgentChatClient;
        this.agentProperties = agentProperties;

        this.mallPolicyTool = mallPolicyTool;

        this.productSearchTool = productSearchTool;

        this.productDetailTool = productDetailTool;

        this.cartTool = cartTool;

        this.listOrdersTool = listOrdersTool;

        this.getOrderDetailTool = getOrderDetailTool;

        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent 模型名称不能为空"
            );
        }

        this.modelName = modelName.strip();
    }

    /**
     * 调用模型并返回文本与 Token 用量分片流。
     *
     * <p>分片中的 contentDelta 只是本次新增片段，不是完整回答。
     * 调用方需要按接收顺序追加。Token Usage 通常只出现在最后一个
     * 分片中，而且该分片可能没有正文。</p>
     *
     * <p>重要：返回的是冷流。每次订阅都会真正调用一次模型。
     * 后续运行编排层必须保证新 AgentRun 只订阅一次，
     * 并通过共享流处理浏览器重复连接。</p>
     *
     * @param messages 系统提示词之外的历史消息和当前用户消息
     * @return 模型生成的文本与 Usage 分片
     */
    public Flux<AgentModelStreamChunk> stream(
            List<Message> messages,
            AgentToolRequestContext toolRequestContext
    ) {
        Objects.requireNonNull(
                toolRequestContext,
                "Agent 工具请求上下文不能为空"
        );

        List<Message> safeMessages =
                validateAndCopyMessages(messages);

        /*
         * Flux.defer 保证模型请求在真正订阅时才开始。
         *
         * 如果这里只直接调用 stream().content()，虽然通常也会返回冷流，
         * 但显式 defer 可以更清楚地限定模型调用生命周期。
         */
        return Flux.defer(
                        () -> shoppingAgentChatClient
                                .prompt()
                                /*
                                 * defaultSystem 已经在 AgentConfig 中注册，
                                 * 这里仅添加历史 USER/ASSISTANT 和当前 USER。
                                 */
                                .messages(safeMessages)
                                /*
                                 * 模型只注册只读查询工具。
                                 *
                                 * propose_add_cart_item 不再交给模型自由选择，
                                 * 所有明确加购、上下文规格选择和失败重试都由
                                 * AgentRunOrchestrationService 在调用模型前路由到
                                 * AgentAddCartDeterministicOrchestrator。这样动作是否
                                 * 创建不再依赖模型是否愿意发起 Tool Calling。
                                 */
                                .tools(
                                        mallPolicyTool,
                                        productSearchTool,
                                        productDetailTool,
                                        cartTool,
                                        listOrdersTool,
                                        getOrderDetailTool
                                )
                                .toolContext(toolRequestContext.toMap())
                                .stream()
                                /*
                                 * 必须使用 chatResponse()，不能继续使用 content()。
                                 * content() 会丢弃没有正文的结束分片，而 OpenAI-compatible
                                 * 服务经常正是在该分片中返回 Token Usage。
                                 */
                                .chatResponse()
                                .map(AgentModelStreamingClient::toStreamChunk)
                )
                /*
                 * 只过滤既没有正文、也没有 Usage 的空分片。
                 *
                 * 不能仅按正文过滤，否则会再次丢失供应商放在结束分片中的
                 * Token 数据；也不能 strip，单独的空格或换行是有效正文。
                 */
                .filter(chunk ->
                        chunk.hasContent()
                                || chunk.hasUsage()
                )
                /*
                 * SDK 请求层已经配置 timeout，这里再限制整个 Reactor 流。
                 *
                 * 后续加入工具后，整个“模型生成 + 工具调用 + 再生成”
                 * 流程也不能超过业务配置的总时长。
                 */
                .timeout(
                        Duration.ofSeconds(
                                agentProperties
                                        .runTimeoutSeconds()
                        )
                );
    }

    /**
     * 把 Spring AI 响应转换为业务分片。
     *
     * <p>包级可见性用于单元测试真实构造的 ChatResponse，确保文本和
     * Usage 的映射不会随着后续流式代码调整而被意外丢失。</p>
     */
    static AgentModelStreamChunk toStreamChunk(
            ChatResponse response
    ) {
        if (response == null) {
            return AgentModelStreamChunk.textOnly("");
        }

        String contentDelta = "";

        if (response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult()
                .getOutput()
                .getText() != null) {
            contentDelta = response.getResult()
                    .getOutput()
                    .getText();
        }

        Usage usage = response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();

        String responseId = response.getMetadata() == null
                ? null
                : response.getMetadata().getId();

        Integer promptTokens = normalizeUsageValue(
                usage == null
                        ? null
                        : usage.getPromptTokens()
        );
        Integer completionTokens = normalizeUsageValue(
                usage == null
                        ? null
                        : usage.getCompletionTokens()
        );

        /*
         * Spring AI 的 EmptyUsage 使用 0/0 表示供应商没有返回 Usage。
         * 这种情况保持 null；如果任一维度大于 0，则保留另一个维度的 0，
         * 例如模型因 stop 条件没有生成正文时 completionTokens=0 仍是事实。
         */
        if (Integer.valueOf(0).equals(promptTokens)
                && Integer.valueOf(0).equals(completionTokens)) {
            promptTokens = null;
            completionTokens = null;
        }

        return new AgentModelStreamChunk(
                contentDelta,
                responseId,
                promptTokens,
                completionTokens
        );
    }

    /**
     * 防御性清理供应商 Usage。
     *
     * <p>兼容供应商若返回非法负值，按“未提供”处理，不能让第三方元数据
     * 破坏本次已经成功生成的回答。</p>
     */
    private static Integer normalizeUsageValue(
            Integer value
    ) {
        if (value == null || value < 0) {
            return null;
        }

        return value;
    }

    /**
     * 返回本客户端实际使用的模型身份。
     *
     * <p>初始化 AgentRun 时需要把该信息写入数据库，
     * 便于以后切换模型后仍能追踪历史运行。</p>
     */
    public AgentModelIdentity modelIdentity() {
        return AgentModelIdentity
                .openAiCompatible(modelName);
    }

    /**
     * 防御性验证模型消息列表。
     */
    private List<Message> validateAndCopyMessages(
            List<Message> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "发送给模型的消息不能为空"
            );
        }

        /*
         * List.copyOf 同时实现：
         *
         * 1. 拒绝列表中的 null 元素；
         * 2. 防止调用模型期间原列表被其他代码修改。
         */
        List<Message> copied = List.copyOf(messages);

        Message lastMessage =
                copied.get(copied.size() - 1);

        /*
         * 当前 Prompt 必须以用户消息结束，模型才知道需要回答什么。
         * 如果最后是 AssistantMessage，通常表示上下文组装顺序出错。
         */
        if (!(lastMessage instanceof UserMessage)) {
            throw new IllegalArgumentException(
                    "模型上下文必须以当前用户消息结束"
            );
        }

        return copied;
    }


}
