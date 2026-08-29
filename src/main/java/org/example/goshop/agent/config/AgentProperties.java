package org.example.goshop.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 买家购物 Agent 的统一配置对象。
 *
 * <p>application.yml 中所有以 {@code goshop.agent} 开头的配置，
 * 都会自动绑定到这个 record。</p>
 *
 * <p>这里不要放模型 API Key。API Key 属于模型供应商配置，
 * 由 {@code spring.ai.openai.api-key} 单独管理。</p>
 *
 * @param enabled              Agent 功能是否启用
 * @param maxHistoryTurns      每次发送给模型的最大历史回合数
 * @param rateLimitPerMinute   单用户每分钟最多发送的消息数
 * @param maxToolCalls         单次运行最多允许调用多少次工具
 * @param runTimeoutSeconds    单次 Agent 运行总超时时间
 * @param actionTtlMinutes     待确认加购动作的有效时间
 * @param maxInputChars        用户单条消息最大字符数
 * @param maxOutputTokens      模型单次回答最大输出 Token
 */
@ConfigurationProperties(prefix = "goshop.agent")
public record AgentProperties (
        boolean enabled,
        int maxHistoryTurns,
        int rateLimitPerMinute,
        int maxToolCalls,
        int runTimeoutSeconds,
        int actionTtlMinutes,
        int maxInputChars,
        int maxOutputTokens
){
    /**
     * record 的紧凑构造器。
     *
     * <p>Spring 完成配置绑定时会自动进入这里。提前校验配置可以让错误配置
     * 在应用启动阶段直接暴露，而不是等用户真正调用 Agent 时才报错。</p>
     */
    public AgentProperties {
        if (maxHistoryTurns < 1 || maxHistoryTurns > 50) {
            throw new IllegalArgumentException(
                    "Agent 历史回合数必须在 1～50 之间"
            );
        }

        if (rateLimitPerMinute < 1 || rateLimitPerMinute > 60) {
            throw new IllegalArgumentException(
                    "Agent 每分钟请求数必须在 1～60 之间"
            );
        }

        if (maxToolCalls < 1 || maxToolCalls > 20) {
            throw new IllegalArgumentException(
                    "Agent 单次工具调用次数必须在 1～20 之间"
            );
        }

        if (runTimeoutSeconds < 5 || runTimeoutSeconds > 180) {
            throw new IllegalArgumentException(
                    "Agent 运行超时必须在 5～180 秒之间"
            );
        }

        if (actionTtlMinutes < 1 || actionTtlMinutes > 60) {
            throw new IllegalArgumentException(
                    "Agent 待确认动作有效期必须在 1～60 分钟之间"
            );
        }

        if (maxInputChars < 100 || maxInputChars > 4000) {
            throw new IllegalArgumentException(
                    "Agent 用户输入上限必须在 100～4000 个字符之间"
            );
        }

        if (maxOutputTokens < 100 || maxOutputTokens > 8000) {
            throw new IllegalArgumentException(
                    "Agent 最大输出 Token 必须在 100～8000 之间"
            );
        }
    }
}
