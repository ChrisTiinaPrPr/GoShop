package org.example.goshop.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 统一处理待支付订单取消。
 *
 * <p>买家主动取消和 RabbitMQ 超时取消必须共用这一服务，确保两条入口都：</p>
 *
 * <ol>
 *     <li>先锁定订单行，与余额支付和支付宝回调竞争同一把数据库锁；</li>
 *     <li>关闭该订单所有 {@code INIT} 状态的本地支付单；</li>
 *     <li>把订单状态更新为 {@code CANCELLED}；</li>
 *     <li>在同一事务写入 {@code ORDER_CANCELLED} Outbox；</li>
 *     <li>由现有 MQ 消费者异步且幂等地恢复库存。</li>
 * </ol>
 *
 * <p>本服务不直接恢复库存。否则 HTTP 请求与 MQ 消费者同时执行时，
 * 容易出现库存重复增加。库存恢复的唯一业务入口仍是
 * {@code order-stock-restore} 消费者。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final String CANCELLED = "CANCELLED";

    private final MallOrderMapper mallOrderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final MqOutboxService outboxService;

    /**
     * 买家主动取消自己的待支付订单。
     *
     * <p>查询条件同时包含订单号和当前 JWT 用户 ID。不存在的订单和
     * 其他用户的订单统一返回 40401，避免泄露订单归属。</p>
     *
     * <p>已经取消的订单按幂等成功处理，便于浏览器在网络超时后安全重试。
     * 已支付、已发货、退款中等其他状态返回 40901。</p>
     */
    @Transactional
    public void cancelByBuyer(Long userId, String orderNo) {
        MallOrder order = mallOrderMapper
                .selectByOrderNoAndUserIdForUpdate(orderNo, userId);

        if (order == null) {
            throw new BusinessException(40401, "订单不存在或无权访问");
        }

        if (CANCELLED.equals(order.getStatus())) {
            // 第一次请求可能已经成功，但响应在网络中丢失；重复请求不再写事件。
            return;
        }

        if (!PENDING_PAYMENT.equals(order.getStatus())) {
            throw new BusinessException(40901, "当前订单状态不能取消");
        }

        cancelLockedPendingOrder(order);

        log.info(
                "买家主动取消待支付订单成功，userId={}，orderId={}，orderNo={}",
                userId,
                order.getId(),
                order.getOrderNo()
        );
    }

    /**
     * 取消已经到达付款截止时间的订单。
     *
     * <p>该方法由 MQ 消费事务调用，使用 {@link Propagation#MANDATORY}
     * 强制要求外层已经开启数据库事务，从而保证消费幂等记录、订单状态和
     * Outbox 要么一起提交，要么一起回滚。</p>
     *
     * @return {@code true} 表示本次完成取消；{@code false} 表示订单已经
     *         被支付或取消，无需再次处理
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelExpiredOrder(Long orderId, LocalDateTime now) {
        MallOrder order = mallOrderMapper.selectByIdForUpdate(orderId);

        if (order == null) {
            // 超时事件引用不存在的订单属于异常数据，应让 MQ 重试并最终进死信。
            throw new IllegalStateException(
                    "超时消息对应订单不存在，orderId=" + orderId
            );
        }

        if (!PENDING_PAYMENT.equals(order.getStatus())) {
            // 支付或其他取消入口已经先获得行锁，当前消息按幂等结束。
            return false;
        }

        if (order.getExpireAt() == null) {
            throw new IllegalStateException(
                    "待支付订单缺少 expireAt，orderId=" + order.getId()
            );
        }

        if (order.getExpireAt().isAfter(now)) {
            // 提前取消会伤害正常支付，因此配置或时钟异常时必须重试/进死信。
            throw new IllegalStateException(
                    "订单超时消息提前到达，orderId=" + order.getId()
            );
        }

        cancelLockedPendingOrder(order);
        return true;
    }

    /**
     * 对已经通过 {@code SELECT ... FOR UPDATE} 锁定的待支付订单执行取消。
     *
     * <p>调用者必须处于数据库事务中并已经校验订单状态。此方法只包含
     * 两个取消入口完全相同的持久化步骤。</p>
     */
    private void cancelLockedPendingOrder(MallOrder order) {
        paymentRecordMapper.closeUnpaidRecordsByOrderId(order.getId());

        order.setStatus(CANCELLED);
        int updatedRows = mallOrderMapper.updateById(order);

        if (updatedRows != 1) {
            throw new BusinessException(50000, "取消订单失败，请稍后重试");
        }

        /*
         * 订单状态和 Outbox 在同一事务提交。
         * 如果事件序列化或插入失败，订单取消与支付单关闭都会回滚。
         */
        outboxService.saveOrderCancelled(order);
    }
}
