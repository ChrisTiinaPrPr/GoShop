package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.*;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.order.dto.OrderAddressSnapshotResponse;
import org.example.goshop.order.dto.OrderDetailItemResponse;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.payment.entity.PaymentRecord;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.example.goshop.product.dto.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MerchantOrderService {
    private final MerchantMapper merchantMapper;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ObjectMapper objectMapper;

    public PageResult<MerchantOrderListResponse> listOrders(
            Long userId, long page, long pageSize, String status,
            String orderNo, LocalDateTime startAt, LocalDateTime endAt
    ) {
        Merchant merchant = currentMerchant(userId);
        LambdaQueryWrapper<MallOrder> query = new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getMerchantId, merchant.getId())
                .eq(StringUtils.hasText(status), MallOrder::getStatus, status)
                .like(StringUtils.hasText(orderNo), MallOrder::getOrderNo,
                        StringUtils.hasText(orderNo) ? orderNo.trim() : null)
                .ge(startAt != null, MallOrder::getCreatedAt, startAt)
                .lt(endAt != null, MallOrder::getCreatedAt, endAt)
                .orderByDesc(MallOrder::getCreatedAt);

        IPage<MallOrder> result = mallOrderMapper.selectPage(new Page<>(page, pageSize), query);
        List<MerchantOrderListResponse> records = result.getRecords().stream()
                .map(this::toListResponse).toList();
        return new PageResult<>(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    public MerchantOrderDetailResponse getOrder(Long userId, String orderNo) {
        Merchant merchant = currentMerchant(userId);
        MallOrder order = mallOrderMapper.selectOne(new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getOrderNo, orderNo)
                .eq(MallOrder::getMerchantId, merchant.getId()));
        if (order == null) throw new BusinessException(40401, "订单不存在或无权访问");

        List<OrderDetailItemResponse> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId())
                        .orderByAsc(OrderItem::getId)
        ).stream().map(OrderDetailItemResponse::from).toList();
        PaymentRecord payment = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId, order.getId())
                        .in(PaymentRecord::getStatus, "PAID", "REFUNDED")
                        .last("LIMIT 1")
        );
        return new MerchantOrderDetailResponse(
                order.getOrderNo(), order.getStatus(), order.getTotalAmountCent(),
                order.getPayAmountCent(), payment == null ? null : payment.getChannel(),
                order.getPaidAt(), order.getShippingCompany(), order.getTrackingNo(),
                order.getShippedAt(), order.getCreatedAt(), parseAddress(order), items);
    }

    /** 只有未发货订单能进入待收货；订单锁避免与退款申请并发。 */
    @Transactional
    public MerchantOrderDetailResponse ship(Long userId, String orderNo, ShipOrderRequest request) {
        Merchant merchant = currentMerchant(userId);
        MallOrder order = mallOrderMapper.selectByOrderNoAndMerchantIdForUpdate(orderNo, merchant.getId());
        if (order == null) throw new BusinessException(40401, "订单不存在或无权访问");
        if (!"WAITING_SHIPMENT".equals(order.getStatus())) {
            throw new BusinessException(40901, "当前订单状态不能发货");
        }
        order.setShippingCompany(request.shippingCompany().trim());
        order.setTrackingNo(request.trackingNo().trim());
        order.setShippedAt(LocalDateTime.now());
        order.setStatus("WAITING_RECEIPT");
        mallOrderMapper.updateById(order);
        return getOrder(userId, orderNo);
    }

    private MerchantOrderListResponse toListResponse(MallOrder order) {
        OrderAddressSnapshotResponse address = parseAddress(order);
        return new MerchantOrderListResponse(order.getOrderNo(), order.getStatus(),
                order.getPayAmountCent(), address.receiver(), address.phone(), order.getPaidAt(),
                order.getShippedAt(), order.getCreatedAt());
    }

    private OrderAddressSnapshotResponse parseAddress(MallOrder order) {
        try {
            return objectMapper.readValue(order.getAddressSnapshotJson(), OrderAddressSnapshotResponse.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50000, "订单地址快照异常");
        }
    }

    private Merchant currentMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(new LambdaQueryWrapper<Merchant>()
                .eq(Merchant::getUserId, userId).eq(Merchant::getStatus, 1));
        if (merchant == null) throw new BusinessException(40301, "商家不存在或已停用");
        return merchant;
    }
}
