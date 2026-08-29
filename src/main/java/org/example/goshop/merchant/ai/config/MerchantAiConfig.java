package org.example.goshop.merchant.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.time.Duration;
import java.util.Map;

/** 注册商家智能导购模块配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        {
                MerchantAiDocumentProperties.class,
                MerchantAiRagProperties.class
        }
)
public class MerchantAiConfig {

    /**
     * 聊天模型和 RAG 都启用时才注册店铺导购专用客户端。
     * 文档管理仍可在聊天模型关闭时使用，不会因缺少 ChatClient 阻止启动。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "goshop.merchant-ai.rag",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnProperty(
            prefix = "spring.ai.model",
            name = "chat",
            havingValue = "openai"
    )
    static class EnabledGuideAnswerConfiguration {

        /**
         * 导购回答只需要低随机性的事实总结，不注册购物 Agent 工具。
         * 商品实时快照由业务 Service 在模型调用前确定性查询并写入 Prompt。
         */
        @Bean(name = "merchantAiGuideChatClient")
        ChatClient merchantAiGuideChatClient(
                ChatClient.Builder builder
        ) {
            OpenAiChatOptions.Builder options =
                    OpenAiChatOptions.builder()
                            .maxTokens(800)
                            .temperature(0.2D)
                            .timeout(Duration.ofSeconds(45))
                            /* 当前兼容供应商关闭思考模式，保证普通回答协议稳定。 */
                            .extraBody(Map.of(
                                    "thinking",
                                    Map.of("type", "disabled")
                            ));
            return builder.defaultOptions(options).build();
        }
    }

    /**
     * 文档解析、Embedding 和向量写入专用线程池。
     *
     * <p>这些操作包含文件解析和外部网络调用，不能占用 Tomcat 请求线程。
     * 队列容量与线程数保持较小，防止商家集中提交大文件时耗尽应用内存。</p>
     */
    @Bean(name = "merchantAiDocumentExecutor")
    public Executor merchantAiDocumentExecutor(
            MerchantAiRagProperties properties
    ) {
        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("merchant-ai-rag-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
