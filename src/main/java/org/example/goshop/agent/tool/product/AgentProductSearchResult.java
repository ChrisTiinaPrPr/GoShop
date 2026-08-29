package org.example.goshop.agent.tool.product;

import java.util.List;

/**
 * search_products 的结构化查询结果。
 */
public record AgentProductSearchResult(
        List<AgentProductSearchItem> items,
        long total,
        boolean hasMore
) {
    public AgentProductSearchResult {
        /*
         * 创建不可变副本，防止工具返回后列表被其他代码修改。
         */
        items = items == null
                ? List.of()
                : List.copyOf(items);

        if (total < 0) {
            throw new IllegalArgumentException(
                    "商品总数不能小于 0"
            );
        }
    }
}
