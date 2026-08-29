package org.example.goshop.agent.dto;

import org.example.goshop.cart.dto.CartItemResponse;

/**
 * 用户确认 Agent 加购动作后的响应。
 *
 * <p>该对象同时会序列化到 agent_action.result_json。
 * 重复确认同一个动作时，服务端直接反序列化第一次的结果，
 * 不能再次调用 CartService。</p>
 */
public record AgentActionConfirmResponse(
        Long actionId,
        String status,
        CartItemResponse cartItem
) {
    public AgentActionConfirmResponse {
        if (actionId == null || actionId <= 0) {
            throw new IllegalArgumentException(
                    "动作 ID 必须为正数"
            );
        }

        /*
         * 只有真正完成购物车写入后才能创建该响应。
         */
        if (!"CONFIRMED".equals(status)) {
            throw new IllegalArgumentException(
                    "确认结果状态必须是 CONFIRMED"
            );
        }

        if (cartItem == null) {
            throw new IllegalArgumentException(
                    "确认结果缺少购物车商品"
            );
        }
    }
}
