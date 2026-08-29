package org.example.goshop.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.dto.*;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.example.goshop.product.dto.PageResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;



/**
 * 负责幂等，PROCESSING 防止并发重复提交，Redis 幂等记录保持 24 小时。
 * 订单成功后 PROCESSING 会被替换成系列化后的订单结果。
 * 24 小时内用同一个幂等键再次提交，会直接返回第一次的订单结果，不会重复创建订单或扣库存。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final String IDEMPOTENCY_PREFIX = "order:submit:";
    private static final String PROCESSING_PREFIX = "PROCESSING:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final int MAX_DATABASE_ATTEMPTS = 3;
    /**
     * 订单摘要查询单次最多返回 10 个订单。
     */
    private static final int MAX_ORDER_SUMMARY_LIMIT = 10;

    /**
     * 订单列表中每个订单最多附带 3 条商品摘要。
     * 完整订单商品由详情接口查询。
     */
    private static final int MAX_SUMMARY_ITEMS_PER_ORDER = 3;

    /**
     * 当前订单业务允许出现在买家订单查询中的状态。
     *
     * <p>状态必须由服务端白名单转换，不能让模型或请求参数直接参与 SQL。</p>
     */
    private static final Set<String> ORDER_STATUS_VALUES =
            Set.of(
                    "PENDING_PAYMENT",
                    "WAITING_SHIPMENT",
                    "WAITING_RECEIPT",
                    "COMPLETED",
                    "CANCELLED",
                    "REFUNDING",
                    "REFUNDED"
            );

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderTransactionService orderTransactionService;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;

    public SubmitOrderResponse submitOrder(
            Long userId,
            String idempotencyKey,
            SubmitOrderRequest request
    ) {
        String redisKey = IDEMPOTENCY_PREFIX + userId + ":" + idempotencyKey;
        String requestHash = calculateRequestHash(request);
        String processingValue = PROCESSING_PREFIX + requestHash;
        boolean redisLockAcquired = tryAcquireRedisLock(redisKey, processingValue);

        try {
            if (!redisLockAcquired) {
                SubmitOrderResponse cached = readCachedResult(redisKey, requestHash);
                if (cached != null) {
                    return cached;
                }
            }

            /*
             * Redis 只用于减少数据库竞争。无论缓存锁是否获得，最终都进入带唯一键的
             * MySQL 幂等事务；这样缓存故障、过期或多实例竞争不会产生重复订单。
             */
            SubmitOrderResponse response = executeWithDeadlockRetry(
                    userId,
                    idempotencyKey,
                    requestHash,
                    request
            );
            cacheCompletedResult(redisKey, requestHash, response);
            return response;
        } catch (RuntimeException e) {
            if (redisLockAcquired) {
                deleteProcessingValue(redisKey, processingValue);
            }
            throw e;
        }
    }

    private boolean tryAcquireRedisLock(
            String redisKey,
            String processingValue
    ) {
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            redisKey,
                            processingValue,
                            IDEMPOTENCY_TTL
                    )
            );
        } catch (RuntimeException redisFailure) {
            // Redis 不可用时降级到 MySQL 唯一键，交易正确性不能依赖缓存可用性。
            log.warn("下单幂等 Redis 锁不可用，降级使用 MySQL，redisKey={}", redisKey);
            return false;
        }
    }

    private SubmitOrderResponse readCachedResult(
            String redisKey,
            String requestHash
    ) {
        String value = null;
        try {
            value = stringRedisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                return null;
            }
            if (value.startsWith(PROCESSING_PREFIX)) {
                String processingHash = value.substring(PROCESSING_PREFIX.length());
                ensureSameRequestHash(processingHash, requestHash);
                // 同请求正在处理时继续进入 MySQL；唯一键会等待并恢复首次提交结果。
                return null;
            }

            IdempotencyCacheValue cached = objectMapper.readValue(
                    value,
                    IdempotencyCacheValue.class
            );
            ensureSameRequestHash(cached.requestHash(), requestHash);
            return cached.response();
        } catch (BusinessException businessException) {
            throw businessException;
        } catch (JsonProcessingException incompatibleCacheValue) {
            return readLegacyCachedResult(redisKey, value);
        } catch (RuntimeException cacheFailure) {
            // 缓存损坏或暂时不可用时回源 MySQL，不把 Redis 数据当作订单事实。
            log.warn("读取下单幂等缓存失败，回源 MySQL，redisKey={}", redisKey);
            return null;
        }
    }

    private SubmitOrderResponse readLegacyCachedResult(
            String redisKey,
            String value
    ) {
        try {
            /*
             * 上线迁移期间，Redis 中可能仍有旧版本直接保存的 SubmitOrderResponse。
             * 旧值没有请求摘要，无法执行新增的请求体冲突判断，但优先复用旧结果可避免
             * 24 小时 TTL 内因 MySQL 尚无历史事实记录而重复创建订单。
             */
            return objectMapper.readValue(value, SubmitOrderResponse.class);
        } catch (JsonProcessingException invalidLegacyValue) {
            log.warn("下单幂等缓存内容无效，回源 MySQL，redisKey={}", redisKey);
            return null;
        }
    }

    private SubmitOrderResponse executeWithDeadlockRetry(
            Long userId,
            String idempotencyKey,
            String requestHash,
            SubmitOrderRequest request
    ) {
        for (int attempt = 1; attempt <= MAX_DATABASE_ATTEMPTS; attempt++) {
            try {
                return orderTransactionService.createOrdersIdempotently(
                        userId,
                        idempotencyKey,
                        requestHash,
                        request
                );
            } catch (TransientDataAccessException transientFailure) {
                if (attempt == MAX_DATABASE_ATTEMPTS) {
                    throw transientFailure;
                }
                /*
                 * 重试发生在 @Transactional 方法之外，每次调用都会开启新事务；
                 * 线性短退避降低两个事务立即再次以相同顺序碰撞的概率。
                 */
                sleepBeforeDatabaseRetry(attempt);
            }
        }
        throw new IllegalStateException("数据库重试流程未返回结果");
    }

    private void sleepBeforeDatabaseRetry(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50000, "订单提交重试被中断");
        }
    }

    private void cacheCompletedResult(
            String redisKey,
            String requestHash,
            SubmitOrderResponse response
    ) {
        try {
            String value = objectMapper.writeValueAsString(
                    new IdempotencyCacheValue(requestHash, response)
            );
            stringRedisTemplate.opsForValue().set(redisKey, value, IDEMPOTENCY_TTL);
        } catch (RuntimeException | JsonProcessingException cacheFailure) {
            /*
             * 此时 MySQL 事务已经保存订单和完整响应。缓存回写失败只影响性能，
             * 不能向客户端报失败，否则客户端重试会误以为第一次没有提交。
             */
            log.warn("下单成功但幂等缓存回写失败，后续请求将回源 MySQL，redisKey={}", redisKey);
        }
    }

    private void deleteProcessingValue(
            String redisKey,
            String processingValue
    ) {
        try {
            String current = stringRedisTemplate.opsForValue().get(redisKey);
            if (processingValue.equals(current)) {
                stringRedisTemplate.delete(redisKey);
            }
        } catch (RuntimeException redisFailure) {
            // PROCESSING 有固定 TTL，清理失败不会影响 MySQL 事务的正确性。
            log.warn("清理失败下单的 Redis PROCESSING 标记失败，redisKey={}", redisKey);
        }
    }

    private void ensureSameRequestHash(
            String existingHash,
            String requestHash
    ) {
        if (!requestHash.equals(existingHash)) {
            throw new BusinessException(
                    40901,
                    "同一 Idempotency-Key 不能用于不同下单请求"
            );
        }
    }

    /**
     * 地址与合并后的 SKU 数量组成规范化载荷，列表顺序和重复 SKU 拆写不影响摘要。
     */
    private String calculateRequestHash(SubmitOrderRequest request) {
        TreeMap<Long, Integer> quantities = new TreeMap<>();
        try {
            request.items().forEach(item -> quantities.merge(
                    item.skuId(),
                    item.quantity(),
                    Math::addExact
            ));
        } catch (ArithmeticException arithmeticException) {
            throw new BusinessException(40001, "购买数量不合法");
        }

        StringBuilder canonical = new StringBuilder()
                .append(request.addressId())
                .append('|');
        quantities.forEach((skuId, quantity) -> canonical
                .append(skuId)
                .append(':')
                .append(quantity)
                .append(';'));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Java 17 规范强制提供 SHA-256；若运行时缺失则属于不可恢复的平台错误。
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", impossible);
        }
    }

    private record IdempotencyCacheValue(
            String requestHash,
            SubmitOrderResponse response
    ) {
    }

    /**
     * 仅查询当前用户自己的订单，防止通过请求参数越权读取其他用户数据。
     */
    public PageResult<OrderListResponse> listUserOrders(Long userId, long page, long pageSize) {
        IPage<MallOrder> orderPage = mallOrderMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getUserId, userId)
                        .orderByDesc(MallOrder::getCreatedAt)
                        .orderByDesc(MallOrder::getId)
        );
        List<OrderListResponse> records = orderPage.getRecords().stream().map(OrderListResponse::from).toList();
        return new PageResult<>(
                records,
                orderPage.getCurrent(),
                orderPage.getSize(),
                orderPage.getTotal()
        );
    }

    /**
     * 查询当前买家的订单摘要，并批量加载少量商品快照。
     *
     * <p>该方法不会读取 addressSnapshotJson，也不会创建
     * OrderAddressSnapshotResponse，因此地址和手机号不会进入调用链。</p>
     *
     * @param userId 当前登录买家 ID，只能来自服务端认证上下文
     * @param status 可选订单状态；null 表示全部状态
     * @param limit  返回数量，只允许 1～10
     */
    public PageResult<OrderSummaryResponse>
    listUserOrderSummaries(
            Long userId,
            String status,
            int limit
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    40001,
                    "用户 ID 必须为正数"
            );
        }

        if (limit < 1
                || limit > MAX_ORDER_SUMMARY_LIMIT) {
            throw new BusinessException(
                    40001,
                    "订单摘要数量必须在 1～10 之间"
            );
        }

        String normalizedStatus = null;

        if (status != null
                && !status.isBlank()) {
            normalizedStatus =
                    status.strip()
                            .toUpperCase(
                                    Locale.ROOT
                            );

            if (!ORDER_STATUS_VALUES.contains(
                    normalizedStatus
            )) {
                throw new BusinessException(
                        40001,
                        "订单状态参数不合法"
                );
            }
        }

        /*
         * 查询条件始终包含 userId。
         * 即使上层工具传错订单状态，也不能移除用户归属条件。
         */
        LambdaQueryWrapper<MallOrder> wrapper =
                new LambdaQueryWrapper<MallOrder>()
                        .eq(
                                MallOrder::getUserId,
                                userId
                        )
                        .orderByDesc(
                                MallOrder::getCreatedAt
                        )
                        .orderByDesc(
                                MallOrder::getId
                        );

        if (normalizedStatus != null) {
            wrapper.eq(
                    MallOrder::getStatus,
                    normalizedStatus
            );
        }

        IPage<MallOrder> orderPage =
                mallOrderMapper.selectPage(
                        new Page<>(1, limit),
                        wrapper
                );

        List<MallOrder> orders =
                orderPage.getRecords();

        if (orders.isEmpty()) {
            return new PageResult<>(
                    List.of(),
                    1,
                    limit,
                    orderPage.getTotal()
            );
        }

        List<Long> orderIds =
                orders.stream()
                        .map(MallOrder::getId)
                        .toList();

        /*
         * 一次性查询这批订单的商品，避免对每个订单执行一次 SQL。
         *
         * 这里只读取 order_item 商品快照，不读取地址快照。
         */
        List<OrderItem> orderItems =
                orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>()
                                .in(
                                        OrderItem::getOrderId,
                                        orderIds
                                )
                                .orderByAsc(
                                        OrderItem::getOrderId
                                )
                                .orderByAsc(
                                        OrderItem::getId
                                )
                );

        Map<Long, List<OrderItem>> itemsByOrderId =
                orderItems.stream()
                        .collect(
                                Collectors.groupingBy(
                                        OrderItem::getOrderId,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        List<OrderSummaryResponse> records =
                orders.stream()
                        .map(order -> {
                            List<OrderItem> allItems =
                                    itemsByOrderId.getOrDefault(
                                            order.getId(),
                                            List.of()
                                    );

                            List<OrderItemSummaryResponse>
                                    visibleItems =
                                    allItems.stream()
                                            .limit(
                                                    MAX_SUMMARY_ITEMS_PER_ORDER
                                            )
                                            .map(
                                                    OrderItemSummaryResponse
                                                            ::from
                                            )
                                            .toList();

                            return OrderSummaryResponse.from(
                                    order,
                                    visibleItems,
                                    allItems.size(),
                                    allItems.size()
                                            > MAX_SUMMARY_ITEMS_PER_ORDER
                            );
                        })
                        .toList();

        return new PageResult<>(
                records,
                orderPage.getCurrent(),
                orderPage.getSize(),
                orderPage.getTotal()
        );
    }

    /**
     * 订单号和当前用户 ID 联合查询。
     * 不属于当前用户的订单统一按“不存在”处理，避免枚举订单号越权。
     */
    public OrderDetailResponse getUserOrderDetail(Long userId,String orderNo) {
        MallOrder order = mallOrderMapper.selectOne(
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getOrderNo, orderNo)
                        .eq(MallOrder::getUserId, userId)
        );

        if (order == null) {
            throw new BusinessException(40401, "订单不存在");
        }

        List<OrderDetailItemResponse> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId())
                        .orderByAsc(OrderItem::getId)
        ).stream().map(OrderDetailItemResponse::from).toList();

        try {
            OrderAddressSnapshotResponse address = objectMapper.readValue(
                    order.getAddressSnapshotJson(),
                    OrderAddressSnapshotResponse.class
            );
            return OrderDetailResponse.from(order, address, items);
        } catch (JsonProcessingException e) {
            // 地址快照来自订单创建事务，无法解析说明历史数据异常
            throw new BusinessException(50000, "订单地址快照异常");
        }
    }

    /**
     * 买家确认收货。订单行锁保证确认收货、退款申请和商家操作不会并发越过状态机。
     */
    @Transactional
    public void confirmReceipt(Long userId, String orderNo) {
        MallOrder order = mallOrderMapper.selectByOrderNoAndUserIdForUpdate(orderNo, userId);
        if (order == null) {
            throw new BusinessException(40401, "订单不存在或无权访问");
        }
        if (!"WAITING_RECEIPT".equals(order.getStatus())) {
            throw new BusinessException(40901, "当前订单状态不能确认收货");
        }
        order.setStatus("COMPLETED");
        mallOrderMapper.updateById(order);
    }

    /**
     * 查询当前买家的隐私安全订单详情。
     *
     * <p>与 getUserOrderDetail() 的区别：</p>
     *
     * <ul>
     *     <li>本方法不会读取或解析地址快照；</li>
     *     <li>不会创建 OrderAddressSnapshotResponse；</li>
     *     <li>返回订单商品的真实购买数量；</li>
     *     <li>适合由买家 Agent 的只读查询门面调用。</li>
     * </ul>
     *
     * @param userId  当前登录买家 ID，只能来自服务端认证上下文
     * @param orderNo 用户自己的订单号
     * @return 不包含地址、姓名和手机号的订单详情
     */
    public OrderSafeDetailResponse getUserOrderSafeDetail(
            Long userId,
            String orderNo
    ) {
        /*
         * 业务 Service 必须再次校验 userId，
         * 不能完全依赖 Agent 工具层已经校验。
         */
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    40001,
                    "用户 ID 必须为正数"
            );
        }

        /*
         * MyBatis 参数虽然会使用预编译方式绑定，不存在直接 SQL 拼接，
         * 但仍需限制订单号长度，避免无意义的超长模型参数进入数据库。
         */
        if (orderNo == null
                || orderNo.isBlank()
                || orderNo.length() > 64) {
            throw new BusinessException(
                    40001,
                    "订单号不合法"
            );
        }

        String normalizedOrderNo =
                orderNo.strip();

        /*
         * SQL 同时校验 orderNo 与 userId，并且不会 SELECT 地址快照。
         *
         * 对于不存在的订单和他人的订单，统一返回“订单不存在”，
         * 避免攻击者根据错误差异枚举订单归属。
         */
        MallOrder order =
                mallOrderMapper
                        .selectSafeDetailByOrderNoAndUserId(
                                normalizedOrderNo,
                                userId
                        );

        if (order == null) {
            throw new BusinessException(
                    40401,
                    "订单不存在"
            );
        }

        /*
         * 商品使用下单时保存的快照。
         * 不再查询当前商品信息替换历史订单事实。
         */
        List<OrderSafeDetailItemResponse> items =
                orderItemMapper
                        .selectByOrderId(order.getId())
                        .stream()
                        .map(
                                OrderSafeDetailItemResponse
                                        ::from
                        )
                        .toList();

        /*
         * 该转换方法只挑选安全字段，
         * 不会读取 MallOrder.addressSnapshotJson。
         */
        return OrderSafeDetailResponse.from(
                order,
                items
        );
    }
}
