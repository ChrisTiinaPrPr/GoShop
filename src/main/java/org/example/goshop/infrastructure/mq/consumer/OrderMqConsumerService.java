package org.example.goshop.infrastructure.mq.consumer;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.lettuce.core.output.ListOfGenericMapsOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.infrastructure.mq.OrderEvent;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.order.service.OrderCancellationService;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消费者事务
 * RabbitMQ 消费者对应的数据库事务。
 *
 * <p>监听方法本身不写业务逻辑，只负责反序列化和调用本服务。
 * 本服务抛出异常时：</p>
 *
 * <ol>
 *     <li>MySQL 事务回滚；</li>
 *     <li>监听方法不能正常返回；</li>
 *     <li>RabbitMQ 消息不会被 ACK；</li>
 *     <li>Spring Retry 执行下一次重试；</li>
 *     <li>最终仍失败则进入死信队列。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMqConsumerService {

    private static final String TIMEOUT_CONSUMER = "order-timeout-cancel";

    private static final String STOCK_CONSUMER = "order-stick-restore";

    private static final String PAID_NOTIFICATION_CONSUMER = "order-paid-notification";

    private final MqConsumeLogMapper consumeLogMapper;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final OrderNotificationMapper notificationMapper;
    private final OrderCancellationService orderCancellationService;
    private final ProductDetailCacheService productDetailCacheService;

    /**
     * 处理订单超时。
     *
     * <p>这里只取消订单和关闭本地支付单，不直接恢复库存。
     * 取消成功后在同一事务写入 ORDER_CANCELLED Outbox，
     * 由另一个 MQ 消费者异步恢复库存。</p>
     */
    @Transactional
    public void handleOrderTimeout(OrderEvent event) {
        String businessKey = String.valueOf(event.orderId());

        if (consumeLogMapper.tryConsume(
                TIMEOUT_CONSUMER,
                businessKey,
                event.eventId()
        ) == 0) {
            log.info("重复订单超时消息，直接 ACK，orderId={}", event.orderId());
            return;
        }

        /*
         * 共享取消服务会锁定订单行，并与余额支付、支付宝回调以及买家主动
         * 取消竞争同一把锁。最终只有最先完成的合法状态迁移生效。
         */
        boolean cancelled = orderCancellationService.cancelExpiredOrder(
                event.orderId(),
                LocalDateTime.now()
        );

        if (cancelled) {
            log.info("MQ 已取消超时订单，orderId={}", event.orderId());
        } else {
            log.info(
                    "订单不再是待支付状态，忽略超时消息，orderId={}",
                    event.orderId()
            );
        }
    }

    /**
     * 恢复已取消订单的库存。
     */
    @Transactional
    public void handleStockRestore(OrderEvent event) {
        String businessKey = String.valueOf(event.orderId());

        /*
         * businessKey 使用订单 ID。
         *
         * 即使同一个订单生成了两个不同 eventId，
         * 也只允许恢复一次库存，避免 stock 被重复增加。
         */
        if (consumeLogMapper.tryConsume(
                STOCK_CONSUMER,
                businessKey,
                event.eventId()
        ) == 0) {
            log.info("订单库存已经恢复，忽略重复消息，orderId={}", event.orderId());
            return;
        }
        MallOrder order = mallOrderMapper.selectByIdForUpdate(event.orderId());

        if (order == null) {
            throw new IllegalStateException("库存恢复对应订单不存在，orderId=" + event.orderId());
        }

        if (!"CANCELLED".equals(order.getStatus())) {
            /*
             * 只允许取消订单恢复库存。
             * 如果支付订单收到恢复事件，继续执行会制造虚假库存。
             */
            throw new IllegalStateException(
                    "非取消订单禁止恢复库存，orderId="
                            + order.getId()
                            + "，status="
                            + order.getStatus()
            );
        }
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId,order.getId())
                /*
                 * 多个订单并发恢复相同 SKU 时，
                 * 固定 SKU 顺序可以降低数据库死锁概率。
                 */
                        .orderByAsc(OrderItem::getSkuId)
        );

        if (items.isEmpty()) {
            throw new IllegalStateException(
                    "取消订单没有订单项，orderId=" + order.getId()
            );
        }

        for (OrderItem item : items) {
            int affectedRows = productSkuMapper.restoreStock(
                    item.getSkuId(),
                    item.getQuantity()
            );
            if (affectedRows != 1) {
                /*
                 * 任何一项失败都抛异常。
                 * @Transactional 会回滚本订单所有已经恢复的库存，
                 * 不会出现只恢复了一部分 SKU 的情况。
                 */
                throw new IllegalStateException(
                        "库存恢复失败，orderId="
                                + order.getId()
                                + "，skuId="
                                + item.getSkuId()
                );
            }
        }
        // 恢复后的可售库存应在本事务提交后反映到公开商品详情。
        productDetailCacheService.evictAfterCommit(
                items.stream().map(OrderItem::getSpuId).toList()
        );
        log.info("MQ 已恢复订单库存，orderId={}", order.getId());
    }
    /**
     * 支付成功异步通知。
     *
     * <p>通知失败不能回滚支付结果，所以通知从支付主事务中拆出，
     * 通过 RabbitMQ 重试。</p>
     */
    public void handlePaidNotification(OrderEvent event) {
        if (event.paymentNo() == null || event.paymentNo().isBlank()) {
            throw new IllegalStateException("支付成功事件缺少 paymentNo");
        }

        /*
         * 支付通知以 paymentNo 为业务唯一键。
         * 支付宝重复回调或 MQ 重复投递都不会重复创建通知。
         */
        if (consumeLogMapper.tryConsume(
                PAID_NOTIFICATION_CONSUMER,
                event.paymentNo(),
                event.eventId()
        ) == 0) {
            log.info("支付成功通知已处理，忽略重复消息，paymentNo={}", event.paymentNo());
            return;
        }
        MallOrder order = mallOrderMapper.selectById(event.orderId());

        if (order == null) {
            throw new IllegalStateException("支付通知对应订单不存在，orderId=" + event.orderId());
        }

        String title = "订单支付成功";
        String content = "订单 " + order.getOrderNo()
                + " 已支付成功，金额 "
                + order.getPayAmountCent()
                + " 分。";

        // 给买家生成一条通知。
        notificationMapper.insertNotification(
                IdWorker.getId(),
                event.eventId(),
                order.getId(),
                "BUYER",
                order.getUserId(),
                title,
                content
        );

        // 给商家生成一条通知。
        notificationMapper.insertNotification(
                IdWorker.getId(),
                event.eventId(),
                order.getId(),
                "MERCHANT",
                order.getMerchantId(),
                title,
                content
        );

        log.info(
                "支付成功通知已生成，orderId={}，paymentNo={}",
                order.getId(),
                event.paymentNo()
        );
    }



}
