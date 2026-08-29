package org.example.goshop.agent.service.model;

/**
 * 一次 Agent 运行实际使用的模型身份。
 *
 * <p>该对象由后续模型编排层传给持久化层。持久化层不应该猜测
 * 当前使用哪个模型，也不能保存 API Key、Base URL 等敏感配置。</p>
 *
 * @param provider 模型供应商类型，例如 openai-compatible
 * @param model    实际模型名称，例如 gpt-5-mini
 */
public record AgentModelIdentity(
        String provider,
        String model
) {
    /**
     * 在写入数据库前验证长度。
     *
     * <p>对应数据库字段：</p>
     *
     * <ul>
     *     <li>provider VARCHAR(50)</li>
     *     <li>model VARCHAR(100)</li>
     * </ul>
     */
    public AgentModelIdentity {
        provider = normalize(provider, "模型供应商不能为空");
        model = normalize(model, "模型名称不能为空");

        if (provider.length() > 50) {
            throw new IllegalArgumentException(
                    "模型供应商标识不能超过 50 个字符"
            );
        }

        if (model.length() > 100) {
            throw new IllegalArgumentException(
                    "模型名称不能超过 100 个字符"
            );
        }
    }

    /**
     * 当前 OpenAI-compatible 接入方式的快捷工厂。
     *
     * <p>后续即使 Base URL 指向其他兼容供应商，
     * provider 仍表示协议适配类型，model 保存实际模型名。</p>
     */
    public static AgentModelIdentity openAiCompatible(String model) {
        return new AgentModelIdentity(
                "openai-compatible",
                model
        );
    }

    private static String normalize(
            String value,
            String emptyMessage
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }

        return value.strip();
    }
}
