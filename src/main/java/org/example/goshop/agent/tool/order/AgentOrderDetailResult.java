package org.example.goshop.agent.tool.order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * get_order_detail 最终返回给模型的结构化结果。
 *
 * <p>该结构中没有地址、收货人、手机号和地址快照。</p>
 */
public record AgentOrderDetailResult(
        String orderNo,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        String shippingCompany,
        String trackingNo,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        List<AgentOrderDetailItem> items,

        /**
         * 该订单实际包含多少条商品记录。
         * 当 itemsTruncated=true 时可能大于 items.size()。
         */
        int itemLineCount,

        /**
         * 全部订单商品的购买数量之和。
         * 即使详情商品被截断，该统计仍代表完整订单。
         */
        long totalQuantity,

        /**
         * 是否因为上下文限制而截断商品列表。
         */
        boolean itemsTruncated
) {
    public AgentOrderDetailResult {
        items = items == null
                ? List.of()
                : List.copyOf(items);

        if (orderNo == null
                || orderNo.isBlank()
                || orderNo.length() > 64) {
            throw new IllegalArgumentException(
                    "订单号不合法"
            );
        }

        if (totalAmountCent == null
                || totalAmountCent < 0
                || payAmountCent == null
                || payAmountCent < 0) {
            throw new IllegalArgumentException(
                    "订单金额不能为负数"
            );
        }

        if (itemLineCount < 0) {
            throw new IllegalArgumentException(
                    "订单商品行数不能为负数"
            );
        }

        if (totalQuantity < 0) {
            throw new IllegalArgumentException(
                    "订单商品总数量不能为负数"
            );
        }
    }
}
