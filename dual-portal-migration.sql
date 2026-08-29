-- 买家端 / 商家端双门户重构迁移脚本（MySQL 8，执行一次）

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role),
    KEY idx_sys_user_role_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 所有历史账号保留买家身份；已有商家额外拥有商家身份。
INSERT IGNORE INTO sys_user_role (user_id, role)
SELECT id, 'USER' FROM sys_user;

INSERT IGNORE INTO sys_user_role (user_id, role)
SELECT user_id, 'MERCHANT' FROM merchant;

ALTER TABLE mall_order
    ADD COLUMN shipping_company VARCHAR(100) NULL COMMENT '物流公司',
    ADD COLUMN tracking_no VARCHAR(100) NULL COMMENT '运单号',
    ADD COLUMN shipped_at DATETIME NULL COMMENT '发货时间';

ALTER TABLE refund_record
    ADD COLUMN order_status_before_refund VARCHAR(32) NULL COMMENT '申请退款前订单状态',
    ADD COLUMN review_remark VARCHAR(255) NULL COMMENT '商家审核意见';

CREATE INDEX idx_mall_order_merchant_status_created
    ON mall_order (merchant_id, status, created_at);

CREATE INDEX idx_refund_status_applied
    ON refund_record (status, applied_at);
