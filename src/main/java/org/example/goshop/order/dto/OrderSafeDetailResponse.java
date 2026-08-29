package org.example.goshop.order.dto;

import org.example.goshop.order.entity.MallOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 不包含收货隐私的订单详情。
 *
 * <p>该 DTO 可供买家购物 Agent 使用，但仍属于订单业务层，
 * 因此不会让 order 模块反向依赖 agent 模块。</p>
 *
 * <p>禁止在此 DTO 中增加以下字段：</p>
 *
 * <ul>
 *     <li>addressSnapshotJson；</li>
 *     <li>收货人姓名；</li>
 *     <li>手机号；</li>
 *     <li>省市区和详细地址。</li>
 * </ul>
 */
public record OrderSafeDetailResponse(
        String orderNo,
        Long merchantId,
        String status,
        Long totalAmountCent,
        Long payAmountCent,
        LocalDateTime expireAt,
        LocalDateTime paidAt,
        String shippingCompany,
        String trackingNo,
        LocalDateTime shippedAt,
        LocalDateTime createdAt,
        List<OrderSafeDetailItemResponse> items
) {
    public OrderSafeDetailResponse {
        /*
         * 防止调用方在 DTO 创建后修改商品列表。
         */
        items = items == null
                ? List.of()
                : List.copyOf(items);
    }

    /**
     * 从订单实体创建隐私安全详情。
     *
     * <p>这里只挑选允许返回的字段，不会访问
     * MallOrder.addressSnapshotJson。</p>
     */
    public static OrderSafeDetailResponse from(
            MallOrder order,
            List<OrderSafeDetailItemResponse> items
    ) {
        return new OrderSafeDetailResponse(
                order.getOrderNo(),
                order.getMerchantId(),
                order.getStatus(),
                order.getTotalAmountCent(),
                order.getPayAmountCent(),
                order.getExpireAt(),
                order.getPaidAt(),
                order.getShippingCompany(),
                order.getTrackingNo(),
                order.getShippedAt(),
                order.getCreatedAt(),
                items
        );
    }
}
