package org.example.goshop.agent.tool.policy;

/**
 * 商城规则主题。
 *
 * <p>这些枚举值以后会直接暴露给模型作为 Tool 参数约束。
 * 使用枚举可以防止模型随意构造未知主题。</p>
 */
public enum MallPolicyTopic {

    /** 支付方式、支付状态和支付限制。 */
    PAYMENT,

    /** 商家发货、物流信息和买家确认收货规则。 */
    SHIPPING,

    /** 退款申请条件、审核方式和当前版本限制。 */
    REFUND,

    /**
     * 返回全部商城规则。
     *
     * <p>ALL 只用于查询参数，不会出现在规则文件的 policies 数组中。</p>
     */
    ALL
}
