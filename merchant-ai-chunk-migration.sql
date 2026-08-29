-- ============================================================
-- 商家智能导购文档分片
--
-- 执行前提：merchant-ai-document-migration.sql 已执行。
-- MySQL 是分片事实源；Qdrant 中的向量可由本表和 OSS 原文件重建。
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_ai_document_chunk
(
    id           BIGINT       NOT NULL COMMENT '分片 ID，由应用生成',
    document_id  BIGINT       NOT NULL COMMENT '所属导购文档 ID',
    assistant_id BIGINT       NOT NULL COMMENT '所属智能导购助手 ID',
    merchant_id  BIGINT       NOT NULL COMMENT '冗余商家 ID，用于租户隔离',
    chunk_index  INT          NOT NULL COMMENT '文档内从 0 开始的分片序号',
    vector_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
                              NOT NULL COMMENT 'Qdrant 确定性 UUID 点 ID',
    content      MEDIUMTEXT   NOT NULL COMMENT '规范化后的分片正文',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                              NOT NULL COMMENT '分片正文 SHA-256',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_ai_chunk_position (document_id, chunk_index),
    UNIQUE KEY uk_merchant_ai_chunk_vector (vector_id),
    KEY idx_merchant_ai_chunk_assistant (assistant_id, id),
    KEY idx_merchant_ai_chunk_merchant (merchant_id, id),

    CONSTRAINT fk_merchant_ai_chunk_document
        FOREIGN KEY (document_id) REFERENCES merchant_ai_document (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_merchant_ai_chunk_assistant
        FOREIGN KEY (assistant_id) REFERENCES merchant_ai_assistant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_ai_chunk_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_merchant_ai_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_merchant_ai_chunk_content CHECK (CHAR_LENGTH(content) > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '商家智能导购文档分片事实表';
