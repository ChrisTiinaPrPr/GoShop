-- ============================================================
-- 1. MQ 本地消息表（Transactional Outbox）
-- ============================================================
CREATE TABLE mq_outbox
(
    id                BIGINT        NOT NULL COMMENT 'MyBatis-Plus 雪花主键',
    event_id          VARCHAR(36)   NOT NULL COMMENT '事件 UUID；也是 MQ messageId',
    aggregate_type    VARCHAR(50)   NOT NULL COMMENT '聚合类型，例如 ORDER',
    aggregate_id      VARCHAR(100)  NOT NULL COMMENT '业务聚合 ID，例如订单 ID',
    event_type        VARCHAR(50)   NOT NULL COMMENT 'ORDER_CREATED/ORDER_PAID/ORDER_CANCELLED',
    exchange_name     VARCHAR(100)  NOT NULL COMMENT '目标交换机',
    routing_key       VARCHAR(100)  NOT NULL COMMENT '目标 routing key',
    payload_json      JSON          NOT NULL COMMENT '事件 JSON',
    status            VARCHAR(20)   NOT NULL DEFAULT 'NEW'
        COMMENT 'NEW=待发送，RETRY=等待重试，SENT=已发送，FAILED=人工处理',
    retry_count       INT           NOT NULL DEFAULT 0 COMMENT '生产者发送重试次数',
    next_retry_at     DATETIME(3)   NOT NULL COMMENT '下一次允许重试时间',
    deliver_at        DATETIME(3)   NULL COMMENT '延迟事件应触发的绝对时间',
    last_error        VARCHAR(1000) NULL COMMENT '最后一次发送失败原因',
    sent_at           DATETIME(3)   NULL COMMENT '收到 RabbitMQ confirm 的时间',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_mq_outbox_event_id (event_id),
    KEY idx_mq_outbox_publish (status, next_retry_at, created_at),
    KEY idx_mq_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
    COMMENT = 'RabbitMQ 本地事务消息表';


-- ============================================================
-- 2. 消费幂等记录
--
-- 不能只用 event_id 去重。
-- 同一个订单可能因为补偿或人工重发而产生不同 event_id，
-- 所以主键使用“消费者名称 + 业务唯一键”。
-- ============================================================
CREATE TABLE mq_consume_log
(
    consumer_name VARCHAR(100) NOT NULL COMMENT '消费者名称',
    business_key  VARCHAR(100) NOT NULL COMMENT '订单 ID 或支付单号',
    event_id      VARCHAR(36)  NOT NULL COMMENT '本次消息事件 ID',
    consumed_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (consumer_name, business_key),
    UNIQUE KEY uk_mq_consume_event (consumer_name, event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
    COMMENT = 'MQ 消费幂等记录';


-- ============================================================
-- 3. 支付成功通知表
--
-- 目前项目没有独立通知中心，因此先把异步通知可靠落库。
-- 前端后续可以查询该表，或再通过 WebSocket 推送。
-- ============================================================
CREATE TABLE order_notification
(
    id            BIGINT       NOT NULL,
    event_id      VARCHAR(36)  NOT NULL COMMENT '来源 MQ 事件 ID',
    order_id      BIGINT       NOT NULL,
    receiver_type VARCHAR(20)  NOT NULL COMMENT 'BUYER 或 MERCHANT',
    receiver_id   BIGINT       NOT NULL COMMENT '用户 ID 或商家 ID',
    type          VARCHAR(50)  NOT NULL COMMENT 'PAYMENT_SUCCESS',
    title         VARCHAR(100) NOT NULL,
    content       VARCHAR(500) NOT NULL,
    read_flag     TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_receiver
        (event_id, receiver_type, receiver_id),
    KEY idx_notification_receiver
        (receiver_type, receiver_id, read_flag, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
    COMMENT = '订单异步通知';