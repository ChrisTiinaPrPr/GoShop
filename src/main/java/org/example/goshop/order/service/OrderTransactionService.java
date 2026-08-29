package org.example.goshop.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.MqProperties;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.dto.SubmitOrderItemRequest;
import org.example.goshop.order.dto.SubmitOrderRequest;
import org.example.goshop.order.dto.SubmitOrderResponse;
import org.example.goshop.order.dto.SubmittedOrderResponse;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.entity.OrderSubmitRecord;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.order.mapper.OrderSubmitRecordMapper;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.example.goshop.user.entity.UserAddress;
import org.example.goshop.user.mapper.UserAddressMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final UserAddressMapper userAddressMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductSpuMapper productSpuMapper;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderSubmitRecordMapper orderSubmitRecordMapper;
    private final ObjectMapper objectMapper;
    private final MqProperties mqProperties;
    private final MqOutboxService outboxService;
    private final ProductDetailCacheService productDetailCacheService;

    @Transactional
    public SubmitOrderResponse createOrders(Long userId, SubmitOrderRequest request) {
        return createOrdersInternal(userId, request);
    }

    /**
     * 在同一个 MySQL 事务中认领幂等键、创建订单并保存响应。
     *
     * <p>唯一键 {@code (user_id, idempotency_key)} 是最终并发边界。Redis 锁失效、
     * 多实例同时进入或缓存写失败时，仍只允许第一个事务创建订单。后续请求会读取
     * 首次事务保存的响应；如果请求摘要不同，则明确返回幂等冲突。</p>
     */
    @Transactional
    public SubmitOrderResponse createOrdersIdempotently(
            Long userId,
            String idempotencyKey,
            String requestHash,
            SubmitOrderRequest request
    ) {
        OrderSubmitRecord record = new OrderSubmitRecord();
        record.setUserId(userId);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setStatus("PROCESSING");

        try {
            orderSubmitRecordMapper.insert(record);
        } catch (DuplicateKeyException duplicateKeyException) {
            /*
             * MySQL 唯一键插入会等待持有冲突键的事务结束。
             * 等待完成后再使用 FOR UPDATE 当前读，可以恢复先提交事务的响应，
             * 同时避免 REPEATABLE READ 快照看不到刚提交的数据。
             */
            OrderSubmitRecord existing = orderSubmitRecordMapper.selectForUpdate(
                    userId,
                    idempotencyKey
            );
            return restoreIdempotentResult(existing, requestHash);
        }

        SubmitOrderResponse response = createOrdersInternal(userId, request);
        record.setStatus("COMPLETED");
        record.setResponseJson(serializeSubmitResponse(response));

        if (orderSubmitRecordMapper.updateById(record) != 1) {
            // 响应事实没有持久化时必须回滚订单，不能只依赖随后可能失败的 Redis 写入。
            throw new BusinessException(50000, "保存下单幂等结果失败");
        }
        return response;
    }

    private SubmitOrderResponse createOrdersInternal(
            Long userId,
            SubmitOrderRequest request
    ) {
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, request.addressId())
                        .eq(UserAddress::getUserId, userId)
        );

        if (address == null) {
            throw new BusinessException(40401,"收货地址不存在");
        }

        // 合并重复购买 SKU 的数量，避免同一 SKU 在一次请求中被拆成多条。
        Map<Long,Integer> quantities = new TreeMap<>();
        try {
            for (SubmitOrderItemRequest item : request.items()) {
                quantities.merge(item.skuId(), item.quantity(), Math::addExact);
            }
        } catch (ArithmeticException e) {
            throw new BusinessException(40001, "购买数量不合法");
        }

        List<ProductSku> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().in(ProductSku::getId, quantities.keySet())
        );

        if (skus.size() != quantities.size()) {
            throw new BusinessException(40401, "部分 SKU 不存在");
        }

        Map<Long,ProductSku> skuMap = skus.stream().collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        Set<Long> spuIds = skus.stream().map(ProductSku::getSpuId).collect(Collectors.toSet());

        Map<Long, ProductSpu> spuMap = productSpuMapper.selectList(
                new LambdaQueryWrapper<ProductSpu>().in(ProductSpu::getId, spuIds)
        ).stream().collect(Collectors.toMap(ProductSpu::getId, Function.identity()));

        Map<Long, List<OrderLine>> merchantLines = new TreeMap<>();

        for (Map.Entry<Long,Integer> entry : quantities.entrySet()) {
            ProductSku sku = skuMap.get(entry.getKey());
            ProductSpu spu = spuMap.get(sku.getSpuId());

            if (spu == null || spu.getStatus() != 1 || sku.getStatus() == null || sku.getStatus() != 1) {
                throw new BusinessException(40901, "商品已下架或 SKU 已禁用");
            }
            // 按商家分组订单商品，如果该商家第一次出现就创建一个空列表，并把当前 SKU 的订单信息加入该商家的列表
            merchantLines.computeIfAbsent(spu.getMerchantId(), ignored -> new ArrayList<>()).add(new OrderLine(spu,sku,entry.getValue()));

        }
        // 条件扣库存，如果库存不足、商品下架、SKU 禁用或不存在则扣减失败，会回滚
        for (ProductSku sku : skus.stream().sorted(Comparator.comparing(ProductSku::getId)).toList()) {
            int affectedRows = productSkuMapper.deductAvailableStock(sku.getId(),quantities.get(sku.getId()));
            if (affectedRows != 1) {
                throw new BusinessException(40902, "商品库存不足,请刷新后重试");
            }
        }

        String addressSnapshot = serializeAddressSnapshot(address);
        /*
         * 订单 expireAt 和 MQ 延迟时间使用同一个配置来源。
         * 避免订单显示 30 分钟过期，但 MQ 却配置成 20 分钟。
         */
        LocalDateTime expireAt = LocalDateTime.now()
                .plus(mqProperties.getOrderTimeout());
        List<SubmittedOrderResponse> responses = new ArrayList<>();
        long totalPayAmountCent = 0;

        for (Map.Entry<Long,List<OrderLine>> entry : merchantLines.entrySet()) {
            long orderAmountCent = calculateAmount(entry.getValue());

            MallOrder order = new MallOrder();
            order.setOrderNo(IdWorker.getIdStr());
            order.setUserId(userId);
            order.setMerchantId(entry.getKey());
            order.setStatus("PENDING_PAYMENT");
            order.setTotalAmountCent(orderAmountCent);
            order.setPayAmountCent(orderAmountCent);
            order.setAddressSnapshotJson(addressSnapshot);
            order.setExpireAt(expireAt);

            mallOrderMapper.insert(order);
            // 使用 Math.multiplyExact 避免金额过大溢出
            for (OrderLine line : entry.getValue()) {
                long subtotalCent = Math.multiplyExact(line.sku().getPriceCent(), line.quantity());

                OrderItem orderItem = new OrderItem();
                orderItem.setOrderId(order.getId());
                orderItem.setSpuId(line.spu().getId());
                orderItem.setSkuId(line.sku().getId());
                orderItem.setProductTitle(line.spu().getTitle());
                orderItem.setProductImage(line.spu().getMainImage());
                orderItem.setSpecsJson(line.sku().getSpecsJson());
                orderItem.setUnitPriceCent(line.sku().getPriceCent());
                orderItem.setQuantity(line.quantity());
                orderItem.setSubtotalCent(subtotalCent);

                orderItemMapper.insert(orderItem);
            }
            /*
             * 必须放在当前订单和订单项成功保存之后。
             */
            outboxService.saveOrderCreated(order);

            // 每张按商家拆分出的订单只累计一次，响应总额应等于各拆分订单应付金额之和。
            totalPayAmountCent = Math.addExact(
                    totalPayAmountCent,
                    orderAmountCent
            );
            responses.add(new SubmittedOrderResponse(
                    order.getOrderNo(),
                    order.getMerchantId(),
                    order.getPayAmountCent(),
                    order.getStatus(),
                    order.getExpireAt()
            ));
        }
        // 库存扣减只有在订单事务提交后才对外可见，此时再批量失效相关商品详情。
        productDetailCacheService.evictAfterCommit(spuIds);
        return new SubmitOrderResponse(responses,totalPayAmountCent);

    }

    private SubmitOrderResponse restoreIdempotentResult(
            OrderSubmitRecord existing,
            String requestHash
    ) {
        if (existing == null) {
            // 唯一键冲突后理论上必须能读到记录；缺失说明数据库状态异常，不允许继续创建订单。
            throw new BusinessException(50000, "下单幂等记录异常");
        }
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(40901, "同一 Idempotency-Key 不能用于不同下单请求");
        }
        if (!"COMPLETED".equals(existing.getStatus())
                || existing.getResponseJson() == null) {
            throw new BusinessException(40901, "订单正在提交，请稍后重试");
        }

        try {
            return objectMapper.readValue(
                    existing.getResponseJson(),
                    SubmitOrderResponse.class
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50000, "下单幂等结果无法解析");
        }
    }

    private String serializeSubmitResponse(SubmitOrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50000, "下单幂等结果无法序列化");
        }
    }

    private long calculateAmount(List<OrderLine> lines) {
        try{
            long total = 0;
            for (OrderLine line: lines) {
                total = Math.addExact(total,Math.multiplyExact(line.sku.getPriceCent(),line.quantity()));
            }
            return total;
        } catch (ArithmeticException e) {
            throw new BusinessException(40001, "订单金额超出范围");
        }
    }

    private String serializeAddressSnapshot(UserAddress address) {
        try {
            return objectMapper.writeValueAsString(
                    new AddressSnapshot(
                            address.getReceiver(),
                            address.getPhone(),
                            address.getProvince(),
                            address.getCity(),
                            address.getDistrict(),
                            address.getDetail()
                    )
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "生成地址快照失败");
        }
    }

    private record OrderLine(ProductSpu spu, ProductSku sku, Integer quantity) {}
    private record AddressSnapshot(
            String receiver,
            String phone,
            String province,
            String city,
            String district,
            String detail
    ) {}
}
