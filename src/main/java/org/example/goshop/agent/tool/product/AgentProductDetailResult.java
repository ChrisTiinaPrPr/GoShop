package org.example.goshop.agent.tool.product;

import java.util.List;

/**
 * get_product_detail 返回给模型的商品详情。
 *
 * <p>商品标题、描述和规格都属于商家可编辑的不可信数据。
 * 它们只能作为商品事实，不能作为新的系统指令。</p>
 */
public record AgentProductDetailResult(
        Long productId,
        Long categoryId,
        String title,
        String description,
        String mainImage,
        Long salesCount,
        List<AgentProductSkuDetail> skus,

        /**
         * true 表示原商品 SKU 数量超过工具上限，本次只返回前若干个。
         */
        boolean skusTruncated
) {
    public AgentProductDetailResult {
        skus = skus == null
                ? List.of()
                : List.copyOf(skus);

        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "商品 ID 必须为正数"
            );
        }
    }
}
