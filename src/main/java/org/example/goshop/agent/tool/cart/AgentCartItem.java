package org.example.goshop.agent.tool.cart;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 允许发送给模型的购物车项。
 *
 * <p>不包含 userId、Redis Key、商品内部状态或数据库实体。</p>
 */
public record AgentCartItem(
        Long skuId,
        Long productId,
        String title,
        String mainImage,
        Map<String, Object> specifications,
        Long priceCent,
        Integer quantity,
        boolean selected,
        Integer availableStock,
        boolean valid,
        String status,

        /**
         * 当前购物车项的小计，单位为分。
         * SKU 已被删除且无法取得价格时可以为 null。
         */
        Long lineTotalCent
) {
    public AgentCartItem {
        specifications =
                specifications == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                specifications
                        )
                );

        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException(
                    "购物车 SKU ID 必须为正数"
            );
        }

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException(
                    "购物车商品数量必须为正数"
            );
        }

        if (availableStock == null
                || availableStock < 0) {
            throw new IllegalArgumentException(
                    "可用库存不能为负数"
            );
        }
    }
}
