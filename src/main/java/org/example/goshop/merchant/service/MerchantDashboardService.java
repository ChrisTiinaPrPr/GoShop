package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.MerchantDashboardResponse;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.example.goshop.refund.entity.RefundRecord;
import org.example.goshop.refund.mapper.RefundRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MerchantDashboardService {
    private static final int LOW_STOCK_THRESHOLD = 10;
    private static final Set<String> EFFECTIVE_PAID_STATUSES = Set.of(
            "WAITING_SHIPMENT", "WAITING_RECEIPT", "COMPLETED", "REFUNDING"
    );

    private final MerchantMapper merchantMapper;
    private final MallOrderMapper mallOrderMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final ProductSpuMapper productSpuMapper;
    private final ProductSkuMapper productSkuMapper;

    public MerchantDashboardResponse getDashboard(Long userId) {
        Merchant merchant = currentMerchant(userId);
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        List<MallOrder> todayPaidOrders = mallOrderMapper.selectList(
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getMerchantId, merchant.getId())
                        .in(MallOrder::getStatus, EFFECTIVE_PAID_STATUSES)
                        .ge(MallOrder::getPaidAt, start)
                        .lt(MallOrder::getPaidAt, end)
        );
        long paidAmount = todayPaidOrders.stream()
                .mapToLong(order -> order.getPayAmountCent() == null ? 0L : order.getPayAmountCent())
                .sum();

        long waitingShipment = mallOrderMapper.selectCount(new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getMerchantId, merchant.getId())
                .eq(MallOrder::getStatus, "WAITING_SHIPMENT"));

        List<Long> orderIds = mallOrderMapper.selectList(new LambdaQueryWrapper<MallOrder>()
                .select(MallOrder::getId).eq(MallOrder::getMerchantId, merchant.getId()))
                .stream().map(MallOrder::getId).toList();
        long pendingRefund = orderIds.isEmpty() ? 0L : refundRecordMapper.selectCount(
                new LambdaQueryWrapper<RefundRecord>()
                        .in(RefundRecord::getOrderId, orderIds)
                        .eq(RefundRecord::getStatus, "PENDING"));

        long onSaleProducts = productSpuMapper.selectCount(new LambdaQueryWrapper<ProductSpu>()
                .eq(ProductSpu::getMerchantId, merchant.getId())
                .eq(ProductSpu::getStatus, 1));
        List<Long> spuIds = productSpuMapper.selectList(new LambdaQueryWrapper<ProductSpu>()
                .select(ProductSpu::getId).eq(ProductSpu::getMerchantId, merchant.getId()))
                .stream().map(ProductSpu::getId).toList();
        long lowStock = spuIds.isEmpty() ? 0L : productSkuMapper.selectCount(
                new LambdaQueryWrapper<ProductSku>()
                        .in(ProductSku::getSpuId, spuIds)
                        .eq(ProductSku::getStatus, 1)
                        .le(ProductSku::getStock, LOW_STOCK_THRESHOLD));

        return new MerchantDashboardResponse((long) todayPaidOrders.size(), paidAmount,
                waitingShipment, pendingRefund, onSaleProducts, lowStock);
    }

    private Merchant currentMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getUserId, userId).eq(Merchant::getStatus, 1));
        if (merchant == null) throw new BusinessException(40301, "商家不存在或已停用");
        return merchant;
    }
}
