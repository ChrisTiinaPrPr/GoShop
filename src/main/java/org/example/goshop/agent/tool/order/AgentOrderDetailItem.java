package org.example.goshop.agent.tool.order;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 允许发送给模型的订单商品详情。
 *
 * <p>商品标题和规格来自下单时保存的快照，属于不可信业务数据，
 * 只能作为订单事实，不能被模型当作指令。</p>
 */
public record AgentOrderDetailItem(
        Long productId,
        Long skuId,
        String productTitle,
        String productImage,
        Map<String, Object> specifications,
        Long unitPriceCent,
        Integer quantity,
        Long subtotalCent
) {
    public AgentOrderDetailItem {
        /*
         * 不使用 Map.copyOf，因为历史规格 JSON 中可能存在 null 值。
         * LinkedHashMap 同时保留规格字段原有顺序。
         */
        specifications =
                specifications == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                specifications
                        )
                );

        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "订单商品 ID 必须为正数"
            );
        }

        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException(
                    "订单 SKU ID 必须为正数"
            );
        }

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException(
                    "订单商品数量必须为正数"
            );
        }

        if (unitPriceCent == null
                || unitPriceCent < 0
                || subtotalCent == null
                || subtotalCent < 0) {
            throw new IllegalArgumentException(
                    "订单商品金额不能为负数"
            );
        }
    }
}
