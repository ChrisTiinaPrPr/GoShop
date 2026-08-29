-- 下单幂等事实表。
-- Redis 仍用于快速拦截，但不能作为已提交订单的唯一事实来源。
CREATE TABLE IF NOT EXISTS order_submit_record (
    id BIGINT NOT NULL COMMENT '雪花 ID',
    user_id BIGINT NOT NULL COMMENT '买家用户 ID',
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '客户端下单幂等键，大小写敏感',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '规范化请求 SHA-256',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'PROCESSING/COMPLETED',
    response_json JSON NULL COMMENT '首次成功下单响应',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_submit_user_key (user_id, idempotency_key),
    KEY idx_order_submit_created_at (created_at),
    CONSTRAINT chk_order_submit_status
        CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT chk_order_submit_completed_response
        CHECK (
            (status = 'PROCESSING' AND response_json IS NULL)
            OR (status = 'COMPLETED' AND response_json IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='下单幂等事实记录';
