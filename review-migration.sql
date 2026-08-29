-- 商品评价模块数据库迁移脚本
-- 适用版本：MySQL 8.0.16+；执行一次。
-- 前置条件：sys_user、product_spu、order_item 表已经存在，且存储引擎为 InnoDB。

CREATE TABLE product_review (
    id BIGINT NOT NULL COMMENT '评价 ID，由应用生成',
    order_item_id BIGINT NOT NULL COMMENT '被评价的订单项 order_item.id',
    user_id BIGINT NOT NULL COMMENT '评价买家 sys_user.id',
    spu_id BIGINT NOT NULL COMMENT '被评价商品 product_spu.id',
    score TINYINT NOT NULL COMMENT '评分，1 到 5 星',
    content VARCHAR(1000) NULL COMMENT '文字评价，最多 1000 字',
    images_json JSON NULL COMMENT '评价图片对象键数组，首版暂不开放上传',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0 隐藏，1 公开',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '评价时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),

    -- 每个订单项只能评价一次；并发重复提交由数据库唯一键最终兜底。
    UNIQUE KEY uk_product_review_order_item (order_item_id),
    KEY idx_product_review_spu_status_created (spu_id, status, created_at, id),
    KEY idx_product_review_user_created (user_id, created_at, id),

    CONSTRAINT fk_product_review_order_item
        FOREIGN KEY (order_item_id) REFERENCES order_item (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_product_review_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_product_review_spu
        FOREIGN KEY (spu_id) REFERENCES product_spu (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_product_review_score CHECK (score BETWEEN 1 AND 5),
    CONSTRAINT ck_product_review_status CHECK (status IN (0, 1))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='已完成订单的商品评价';
