package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.MerchantRefundResponse;
import org.example.goshop.merchant.dto.ReviewRefundRequest;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.payment.entity.PaymentRecord;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.refund.entity.RefundRecord;
import org.example.goshop.refund.mapper.RefundRecordMapper;
import org.example.goshop.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MerchantRefundService {
    private final MerchantMapper merchantMapper;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ProductSkuMapper productSkuMapper;
    private final WalletService walletService;
    private final ProductDetailCacheService productDetailCacheService;

    public PageResult<MerchantRefundResponse> list(Long userId, long page, long pageSize, String status) {
        Merchant merchant = currentMerchant(userId);
        List<Long> orderIds = mallOrderMapper.selectList(new LambdaQueryWrapper<MallOrder>()
                .select(MallOrder::getId)
                .eq(MallOrder::getMerchantId, merchant.getId()))
                .stream().map(MallOrder::getId).toList();
        if (orderIds.isEmpty()) return new PageResult<>(List.of(), page, pageSize, 0);

        LambdaQueryWrapper<RefundRecord> query = new LambdaQueryWrapper<RefundRecord>()
                .in(RefundRecord::getOrderId, orderIds)
                .eq(StringUtils.hasText(status), RefundRecord::getStatus, status)
                .orderByDesc(RefundRecord::getAppliedAt);
        IPage<RefundRecord> result = refundRecordMapper.selectPage(new Page<>(page, pageSize), query);
        List<MerchantRefundResponse> records = result.getRecords().stream().map(this::toResponse).toList();
        return new PageResult<>(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    public MerchantRefundResponse detail(Long userId, String refundNo) {
        Merchant merchant = currentMerchant(userId);
        RefundRecord refund = refundRecordMapper.selectOne(new LambdaQueryWrapper<RefundRecord>()
                .eq(RefundRecord::getRefundNo, refundNo));
        if (refund == null) throw new BusinessException(40401, "退款单不存在");
        MallOrder order = mallOrderMapper.selectById(refund.getOrderId());
        if (order == null || !merchant.getId().equals(order.getMerchantId())) {
            throw new BusinessException(40401, "退款单不存在或无权访问");
        }
        return toResponse(refund);
    }

    /** 审核通过余额退款；支付宝渠道在首版明确拒绝且不改变状态。 */
    @Transactional
    public MerchantRefundResponse approve(Long userId, String refundNo, ReviewRefundRequest request) {
        Merchant merchant = currentMerchant(userId);
        RefundRecord snapshot = refundRecordMapper.selectOne(new LambdaQueryWrapper<RefundRecord>()
                .eq(RefundRecord::getRefundNo, refundNo));
        if (snapshot == null) throw new BusinessException(40401, "退款单不存在");

        MallOrder order = mallOrderMapper.selectByIdForUpdate(snapshot.getOrderId());
        if (order == null || !merchant.getId().equals(order.getMerchantId())) {
            throw new BusinessException(40401, "退款单不存在或无权访问");
        }
        RefundRecord refund = refundRecordMapper.selectByRefundNoForUpdate(refundNo);
        if (!"PENDING".equals(refund.getStatus()) || !"REFUNDING".equals(order.getStatus())) {
            throw new BusinessException(40901, "该退款申请已经处理或订单状态异常");
        }
        PaymentRecord payment = paymentRecordMapper.selectById(refund.getPaymentId());
        if (payment == null || !"PAID".equals(payment.getStatus())) {
            throw new BusinessException(42201, "原支付记录异常");
        }
        if (!"BALANCE".equals(payment.getChannel())) {
            throw new BusinessException(42201, "当前版本暂不支持支付宝自动退款");
        }

        walletService.creditForRefund(order.getUserId(), refund.getRefundNo(), refund.getAmountCent());
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()).orderByAsc(OrderItem::getSkuId));
        for (OrderItem item : items) {
            if (productSkuMapper.restoreStock(item.getSkuId(), item.getQuantity()) != 1) {
                throw new BusinessException(50000, "退款恢复库存失败，skuId=" + item.getSkuId());
            }
        }
        // 退款恢复库存提交后，公开详情必须重新读取最新可售库存。
        productDetailCacheService.evictAfterCommit(
                items.stream().map(OrderItem::getSpuId).toList()
        );

        LocalDateTime now = LocalDateTime.now();
        refund.setStatus("SUCCESS");
        refund.setReviewRemark(normalizeRemark(request.reviewRemark()));
        refund.setProcessedAt(now);
        refundRecordMapper.updateById(refund);
        payment.setStatus("REFUNDED");
        paymentRecordMapper.updateById(payment);
        order.setStatus("REFUNDED");
        mallOrderMapper.updateById(order);
        return toResponse(refund);
    }

    @Transactional
    public MerchantRefundResponse reject(Long userId, String refundNo, ReviewRefundRequest request) {
        Merchant merchant = currentMerchant(userId);
        RefundRecord snapshot = refundRecordMapper.selectOne(new LambdaQueryWrapper<RefundRecord>()
                .eq(RefundRecord::getRefundNo, refundNo));
        if (snapshot == null) throw new BusinessException(40401, "退款单不存在");
        MallOrder order = mallOrderMapper.selectByIdForUpdate(snapshot.getOrderId());
        if (order == null || !merchant.getId().equals(order.getMerchantId())) {
            throw new BusinessException(40401, "退款单不存在或无权访问");
        }
        RefundRecord refund = refundRecordMapper.selectByRefundNoForUpdate(refundNo);
        if (!"PENDING".equals(refund.getStatus()) || !"REFUNDING".equals(order.getStatus())) {
            throw new BusinessException(40901, "该退款申请已经处理或订单状态异常");
        }
        if (!StringUtils.hasText(refund.getOrderStatusBeforeRefund())) {
            throw new BusinessException(50000, "退款申请缺少原订单状态");
        }
        refund.setStatus("REJECTED");
        refund.setReviewRemark(normalizeRemark(request.reviewRemark()));
        refund.setProcessedAt(LocalDateTime.now());
        refundRecordMapper.updateById(refund);
        order.setStatus(refund.getOrderStatusBeforeRefund());
        mallOrderMapper.updateById(order);
        return toResponse(refund);
    }

    private MerchantRefundResponse toResponse(RefundRecord refund) {
        MallOrder order = mallOrderMapper.selectById(refund.getOrderId());
        PaymentRecord payment = paymentRecordMapper.selectById(refund.getPaymentId());
        return new MerchantRefundResponse(refund.getRefundNo(), order.getOrderNo(), order.getStatus(),
                refund.getStatus(), payment == null ? null : payment.getChannel(), refund.getAmountCent(),
                refund.getReason(), refund.getReviewRemark(), refund.getAppliedAt(), refund.getProcessedAt());
    }

    private String normalizeRemark(String remark) {
        return StringUtils.hasText(remark) ? remark.trim() : null;
    }

    private Merchant currentMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getUserId, userId).eq(Merchant::getStatus, 1));
        if (merchant == null) throw new BusinessException(40301, "商家不存在或已停用");
        return merchant;
    }
}
