-- 商品收藏模块数据库迁移脚本
-- 适用版本：MySQL 8.0；执行一次。
-- 前置条件：sys_user、product_spu 表已经存在，且存储引擎为 InnoDB。
-- 项目使用 MyBatis-Plus ASSIGN_ID，因此主键由应用生成，不使用 AUTO_INCREMENT。

CREATE TABLE product_favorite (
    id BIGINT NOT NULL COMMENT '收藏记录 ID，由应用生成',
    user_id BIGINT NOT NULL COMMENT '买家 sys_user.id',
    spu_id BIGINT NOT NULL COMMENT '商品 product_spu.id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间',

    PRIMARY KEY (id),

    -- 数据库唯一键是并发幂等的最终保障，同一买家不能重复收藏同一商品。
    UNIQUE KEY uk_product_favorite_user_spu (user_id, spu_id),

    -- 支持按买家和收藏时间倒序分页；id 用于相同时间下的稳定排序。
    KEY idx_product_favorite_user_created (user_id, created_at, id),
    KEY idx_product_favorite_spu (spu_id),

    CONSTRAINT fk_product_favorite_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_product_favorite_spu
        FOREIGN KEY (spu_id) REFERENCES product_spu (id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='买家商品收藏';
