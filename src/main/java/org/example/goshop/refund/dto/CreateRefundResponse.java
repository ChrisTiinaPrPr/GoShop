package org.example.goshop.refund.dto;

import org.example.goshop.refund.entity.RefundRecord;

import java.time.LocalDateTime;

public record CreateRefundResponse(
        String refundNo,
        String orderNo,
        Long amountCnet,
        String status,
        LocalDateTime appliedAt
) {
    public static CreateRefundResponse from(
            RefundRecord refundRecord,
            String orderNo
    ) {
        return new CreateRefundResponse(
                refundRecord.getRefundNo(),
                orderNo,
                refundRecord.getAmountCent(),
                refundRecord.getStatus(),
                refundRecord.getAppliedAt()
        );
    }
}
