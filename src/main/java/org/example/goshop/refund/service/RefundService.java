package org.example.goshop.refund.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.payment.entity.PaymentRecord;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.example.goshop.refund.dto.CreateRefundRequest;
import org.example.goshop.refund.dto.CreateRefundResponse;
import org.example.goshop.refund.entity.RefundRecord;
import org.example.goshop.refund.mapper.RefundRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RefundService {

    /**
     * 当前允许申请退款的订单状态
     * <p>PENDING_PAYMENT 和 CANCELLED 没有发生支付，不能退款；
     * COMPLETED 的售后期限当前项目尚未实现，因此暂不允许直接退款。</p>
     */

    private static final Set<String> REFUNDABLE_ORDER_STATUSES = Set.of(
            "PAID",
            "WAITING_SHIPMENT"
    );

    private final MallOrderMapper mallOrderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;

    /**
     * 用户申请整单退款。
     *
     * <p>订单查询会加行锁，防止用户重复点击产生两条退款申请，
     * 也防止申请退款与商家发货同时修改同一订单。</p>
     */

    @Transactional
    public CreateRefundResponse applyRefund(Long userId, String orderNo, CreateRefundRequest request) {
        /*
         * 使用订单号和当前 JWT 用户 ID 联合查询。
         * 即使用户猜到其他人的订单号，也无法申请退款。
         */

        MallOrder order = mallOrderMapper.selectByOrderNoAndUserIdForUpdate(orderNo,userId);

        if (order == null) {
            throw new BusinessException(40401, "订单不存在或无权访问");
        }

        if ("REFUNDING".equals(order.getStatus())) {
            throw new BusinessException(40901, "该订单已经提交退款申请");
        }

        if ("REFUNDED".equals(order.getStatus())) {
            throw new BusinessException(40901, "该订单已经退款完成");
        }

        if (!REFUNDABLE_ORDER_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(
                    40901,
                    "当前订单状态不能申请退款"
            );
        }

        /*
         * 退款必须找到原始成功支付记录。
         * 不能使用 INIT、CLOSED 等未支付记录，也不能由前端指定支付渠道。
         */
        PaymentRecord paymentRecord = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId,order.getId())
                        .eq(PaymentRecord::getStatus,"PAID")
        );

        if (paymentRecord == null) {
            throw new BusinessException(42201, "未找到订单成功支付记录");
        }

        if (paymentRecord.getAmountCent() == null
                || !paymentRecord.getAmountCent().equals(order.getPayAmountCent())) {
            throw new BusinessException(42201, "订单金额与支付金额不一致");
        }

        /*
         * 锁定订单后再检查历史退款记录。
         * 正常情况下 REFUNDING 状态已经能阻止重复申请；
         * 这里用于检查历史异常数据。
         */
        Long refundCount = refundRecordMapper.selectCount(
                new LambdaQueryWrapper<RefundRecord>()
                        .eq(RefundRecord::getOrderId,order.getId())
                        .in(
                                RefundRecord::getStatus,
                                "PENDING",
                                "PROCESSING",
                                "SUCCESS"
                        )
        );

        if (refundCount > 0) {
            throw new BusinessException(40901, "该订单存在未结束的退款记录");
        }

        LocalDateTime appliedAt = LocalDateTime.now();

        RefundRecord refundRecord = new RefundRecord();
        refundRecord.setRefundNo(IdWorker.getIdStr());
        refundRecord.setOrderId(order.getId());
        refundRecord.setPaymentId(paymentRecord.getId());

        // @NotBlank 已完成基础校验，这里去除用户输入首尾空格。
        refundRecord.setReason(request.reason().trim());

        // 当前接口为整单退款，金额只能取自成功支付记录。
        refundRecord.setAmountCent(paymentRecord.getAmountCent());
        refundRecord.setStatus("PENDING");
        refundRecord.setAppliedAt(appliedAt);
        refundRecord.setOrderStatusBeforeRefund(order.getStatus());

        refundRecordMapper.insert(refundRecord);

        /*
         * 申请成功后立即进入退款中，阻止商家继续发货。
         * 退款记录插入或订单更新任一步失败，整个事务都会回滚。
         */
        order.setStatus("REFUNDING");
        mallOrderMapper.updateById(order);
        return CreateRefundResponse.from(
                refundRecord,
                order.getOrderNo()
        );
    }


}
