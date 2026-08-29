package org.example.goshop.review.dto;

import lombok.Data;

/**
 * 创建评价时锁定查询得到的可信订单项信息。
 */
@Data
public class ReviewableOrderItem {

    private Long orderItemId;
    private Long orderId;
    private Long spuId;
    private String orderStatus;
}
