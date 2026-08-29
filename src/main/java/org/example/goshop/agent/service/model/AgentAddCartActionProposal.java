package org.example.goshop.agent.service.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建待确认加购动作后的服务端结果。
 *
 * <p>该结果后续同时用于：</p>
 *
 * <ul>
 *     <li>propose_add_cart_item 的工具返回值；</li>
 *     <li>ACTION_REQUIRED SSE 确认卡片；</li>
 *     <li>测试动作是否只停留在 PENDING 状态。</li>
 * </ul>
 */
public record AgentAddCartActionProposal(
        Long actionId,
        String actionType,
        String status,
        Long productId,
        Long skuId,
        Integer quantity,
        String productTitle,
        Map<String, Object> specifications,
        Long unitPriceCent,
        String imageUrl,
        Instant expiresAt
) {
    public AgentAddCartActionProposal {
        if (actionId == null || actionId <= 0) {
            throw new IllegalArgumentException(
                    "待确认动作 ID 必须为正数"
            );
        }

        if (!"ADD_CART_ITEM".equals(actionType)) {
            throw new IllegalArgumentException(
                    "待确认动作类型不合法"
            );
        }

        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException(
                    "新建动作必须处于 PENDING 状态"
            );
        }

        if (productId == null || productId <= 0
                || skuId == null || skuId <= 0) {
            throw new IllegalArgumentException(
                    "待确认动作商品参数不合法"
            );
        }

        if (quantity == null
                || quantity <= 0
                || quantity > 99) {
            throw new IllegalArgumentException(
                    "待确认动作数量必须在 1～99 之间"
            );
        }

        if (unitPriceCent == null
                || unitPriceCent < 0) {
            throw new IllegalArgumentException(
                    "待确认动作价格不能为负数"
            );
        }

        if (expiresAt == null) {
            throw new IllegalArgumentException(
                    "待确认动作过期时间不能为空"
            );
        }

        specifications =
                specifications == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                specifications
                        )
                );
    }
}
