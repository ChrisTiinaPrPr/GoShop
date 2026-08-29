package org.example.goshop.agent.tool.product;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 允许发送给模型的 SKU 公开信息。
 *
 * <p>只包含 SKU ID、规格、售价和可用库存。
 * 不包含原始库存、锁定库存、数据库版本和内部状态。</p>
 */
public record AgentProductSkuDetail(
        Long skuId,
        Map<String, Object> specifications,
        Long priceCent,
        Integer availableStock
) {
    public AgentProductSkuDetail {
        /*
         * 复制规格 Map，防止工具返回后内容被其他代码修改。
         *
         * 不使用 Map.copyOf，因为商品规格 JSON 中理论上可能出现 null，
         * 而 Map.copyOf 不允许键或值为 null。
         */
        specifications =
                specifications == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                specifications
                        )
                );

        if (priceCent == null || priceCent < 0) {
            throw new IllegalArgumentException(
                    "SKU 价格不能为负数"
            );
        }

        if (availableStock == null
                || availableStock < 0) {
            throw new IllegalArgumentException(
                    "SKU 可用库存不能为负数"
            );
        }
    }
}
