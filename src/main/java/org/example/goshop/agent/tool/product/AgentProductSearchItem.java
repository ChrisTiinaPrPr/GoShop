package org.example.goshop.agent.tool.product;

import org.example.goshop.product.dto.ProductListResponse;

/**
 * 允许发送给模型的商品搜索摘要。
 *
 * <p>不要直接把商品实体交给模型。该 DTO 只包含推荐和商品卡片需要的
 * 公开字段，不包含成本价、内部状态、数据库版本等字段。</p>
 */
public record AgentProductSearchItem(
        Long productId,
        Long categoryId,
        String title,
        String mainImage,
        Long minPriceCent,
        Long salesCount
) {
    public static AgentProductSearchItem from(
            ProductListResponse source
    ) {
        return new AgentProductSearchItem(
                source.id(),
                source.categoryId(),
                source.title(),
                source.mainImage(),
                source.minPriceCent(),
                source.salesCount()
        );
    }
}
