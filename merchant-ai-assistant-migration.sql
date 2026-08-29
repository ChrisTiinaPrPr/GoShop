-- ============================================================
-- 商家智能导购助手配置
--
-- 执行前提：dual-portal-migration.sql 已执行，merchant 表已存在。
-- 本文件只创建助手配置；导购文档和向量分片将在后续迁移中增加。
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_ai_assistant
(
    id              BIGINT       NOT NULL COMMENT '助手 ID，由应用生成',
    merchant_id     BIGINT       NOT NULL COMMENT '所属商家 merchant.id',
    name            VARCHAR(60)  NOT NULL COMMENT '买家可见的助手名称',
    avatar_url      VARCHAR(500) NULL COMMENT '助手头像 URL；为空时展示店铺 Logo',
    welcome_message VARCHAR(500) NOT NULL COMMENT '买家进入助手时展示的欢迎语',
    enabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '0-关闭，1-启用',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_ai_assistant_merchant (merchant_id),

    CONSTRAINT fk_merchant_ai_assistant_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_merchant_ai_assistant_enabled
        CHECK (enabled IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '商家智能导购助手配置';
