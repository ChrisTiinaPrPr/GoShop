package org.example.goshop.agent.config;

import org.example.goshop.agent.prompt.AgentSystemPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * 买家购物 Agent 的 Spring 配置。
 *
 * <p>外层配置始终注册 AgentProperties，因此即使模型关闭，
 * 会话列表等数据库接口仍然可以读取 Agent 业务配置。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    /**
     * 只有以下两个条件同时满足时才创建模型客户端：
     *
     * <ol>
     *     <li>goshop.agent.enabled=true；</li>
     *     <li>spring.ai.model.chat=openai。</li>
     * </ol>
     *
     * <p>该条件只控制 ChatClient，不影响其他商城模块。</p>
     */
    @Configuration(proxyBeanMethods = false)
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
    static class EnabledAgentConfiguration {

        /**
         * 创建购物 Agent 专用的 ChatClient。
         *
         * ChatClient 默认配置只注册系统提示词和模型输出限制。
         * 具体工具由 AgentModelStreamingClient 使用单次请求的 tools()
         * 注册，避免不同 Agent 共享错误的工具白名单。
         */
        @Bean
        ChatClient shoppingAgentChatClient(
                ChatClient.Builder builder,
                AgentProperties agentProperties
        ) {
            /*
             * OpenAiChatOptions 是一次模型调用的默认选项。
             *
             * maxCompletionTokens 控制模型最多生成多少 Token，
             * 防止回答无限增长并控制调用成本。
             */
            OpenAiChatOptions.Builder options =
                    OpenAiChatOptions.builder();

            options.maxTokens(
                    agentProperties.maxOutputTokens()
            );

            /*
             * 请求 OpenAI-compatible 服务在流结束时返回 Usage。
             *
             * 流式协议通常把 Prompt/Completion Token 放在最后一个没有
             * 正文的分片中。AgentModelStreamingClient 会保留该分片并把
             * 供应商原始统计写入 agent_run；不使用字符数自行估算。
             */
            options.streamUsage(true);

            /*
             * SDK 层请求超时与业务总超时保持一致。
             *
             * 后续 Reactor 流还会再设置一次 timeout，
             * 因为 Tool Calling 等完整 Agent 流程也必须受总时长限制。
             */
            options.timeout(
                    Duration.ofSeconds(
                            agentProperties.runTimeoutSeconds()
                    )
            );

            /*
             * DeepSeek V4 默认启用思考模式。
             *
             * 当前 Agent 使用 Spring AI 的 OpenAI-compatible ChatClient 自动完成：
             *
             * 模型请求工具 -> Java 工具执行 -> 工具结果返回模型 -> 模型生成最终回答
             *
             * DeepSeek 思考模式的工具调用消息还包含 reasoning_content，
             * 这部分内容必须在工具执行后的下一轮请求中正确回传。
             * 为了让首期工具调用链保持稳定，这里暂时关闭思考模式。
             *
             * 注意：
             * 1. 这是 DeepSeek 专用扩展参数，不属于标准 OpenAI 参数；
             * 2. 将来切换到 OpenAI 或其他兼容供应商时，应将它提取为供应商配置；
             * 3. 关闭思考模式不影响流式输出和 Tool Calling。
             */
            options.extraBody(
                    Map.of(
                            "thinking",
                            Map.of(
                                    "type",
                                    "disabled"
                            )
                    )
            );

            return builder
                    /*
                     * defaultSystem 会自动作为 system 角色消息，
                     * 它不会出现在用户可见的消息历史中。
                     */
                    .defaultSystem(
                            AgentSystemPrompt.CONTENT
                    )
                    .defaultOptions(options)
                    /*
                     * 这一阶段不要调用 defaultTools()。
                     * 工具必须逐个完成鉴权和脱敏后才能注册。
                     */
                    .build();
        }
    }
}
