package org.example.goshop.infrastructure.mq;

/**
 * RabbitMQ 交换机、队列和路由键的统一定义。
 *
 * <p>不要在生产者和消费者中重复手写字符串，否则一个字符写错，
 * 消息就可能无法路由到正确队列。</p>
 */
public final class RabbitMqNames {

    private RabbitMqNames() {
        // 工具类不允许被实例化
    }

    // 订单业务主题交换机
    public static final String ORDER_EXCHANGE = "goshop.order.exchange";

    // TTL 到期后的订单超时消息进入该交换机
    public static final String ORDER_TIMEOUT_EXCHANGE = "goshop.order.timeout.exchange";

    // 所有最终失败消息进入该死信交换机
    public static final String DEAD_LETTER_EXCHANGE = "goshop.dead.exchange";

    // 保存订单创建消息，但不启动消费者，只等待 TTL 到期
    public static final String ORDER_TIMEOUT_DELAY_QUEUE = "goshop.order.timeout.delay.queue";

    // 真正执行订单超时取消的消费者队列
    public static final String ORDER_TIMEOUT_QUEUE = "goshop.order.timeout.queue";

    // 支付成功异步通知队列
    public static final String ORDER_PAID_NOTIFICATION_QUEUE = "goshop.order.paid.notification.queue";

    // 订单取消后的库存恢复队列
    public static final String STOCK_RESTORE_QUEUE = "goshop.order.stock.restore.queue";

    // 所有消费者最终失败后进入的死信队列
    public static final String DEAD_LETTER_QUEUE = "goshop.dead.queue";

    public static final String ORDER_CREATED_KEY = "order.created";
    public static final String ORDER_TIMEOUT_KEY = "order.timeout";
    public static final String ORDER_PAID_KEY = "order.paid";
    public static final String ORDER_CANCELLED_KEY = "order.cancelled";

    public static final String DEAD_ORDER_TIMEOUT_KEY = "dead.order.timeout";

    public static final String DEAD_ORDER_PAID_KEY = "dead.order.paid";

    public static final String DEAD_STOCK_RESTORE_KEY = "dead.order.stock.restore";
}
