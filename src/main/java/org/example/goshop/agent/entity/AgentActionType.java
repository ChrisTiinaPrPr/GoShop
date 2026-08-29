package org.example.goshop.agent.entity;

/**
 * Agent 可以建议用户执行的业务动作类型。
 *
 * <p>首期只开放加入购物车。下单、支付、退款和确认收货
 * 不得加入该枚举，否则模型可能获得超出首期安全边界的能力。</p>
 */
public enum AgentActionType {

    /**
     * 将指定 SKU 和数量加入当前买家的购物车。
     *
     * <p>模型只能创建待确认动作，不能直接调用 CartService。</p>
     */
    ADD_CART_ITEM
}
