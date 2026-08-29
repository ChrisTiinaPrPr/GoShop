package org.example.goshop.refund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退款申请记录
 * <p>申请退款只创建记录并进入 PENDING 状态
 * 真正退回余额或调用第三方退款接口，需要在商家审核通过后执行。</p>
 */
@Data
@TableName("refund_record")
public class RefundRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 商城退款单号
    private String refundNo;

    // 关联商城订单 ID
    private Long orderId;

    // 原支付记录 ID，退款必须沿原支付渠道处理
    private  Long paymentId;

    private String reason;

    // 退款金额，单位：分。当前接口只支持整单退款。
    private Long amountCent;

    /**
     * PENDING：等待商家审核
     * PROCESSING：退款执行中
     * SUCCESS：退款成功
     * REJECTED：商家拒绝
     * FAILED：退款失败
     */
    private String status;

    // 支付宝第三方渠道放回的退款流水号
    private String thirdPartyNo;

    /** 退款被拒绝时恢复到该订单状态。 */
    private String orderStatusBeforeRefund;

    /** 商家审核说明。 */
    private String reviewRemark;

    private LocalDateTime appliedAt;
    private LocalDateTime processedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
