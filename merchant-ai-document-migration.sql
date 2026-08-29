-- ============================================================
-- 商家智能导购文档元数据
--
-- 执行前提：merchant-ai-assistant-migration.sql 已执行。
-- 原文件保存到私有 OSS，本表不保存文件正文；解析分片表后续增加。
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_ai_document
(
    id                BIGINT       NOT NULL COMMENT '文档 ID，由应用生成',
    assistant_id      BIGINT       NOT NULL COMMENT '所属智能导购助手 ID',
    merchant_id       BIGINT       NOT NULL COMMENT '冗余商家 ID，用于租户隔离查询',
    original_filename VARCHAR(255) NOT NULL COMMENT '经过路径清理的原始文件名',
    object_key        VARCHAR(500) NOT NULL COMMENT '私有 OSS 对象键，禁止保存签名 URL',
    file_type         VARCHAR(16)  NOT NULL COMMENT 'PDF、DOCX、TXT 或 MARKDOWN',
    mime_type         VARCHAR(100) NOT NULL COMMENT '服务端识别后的标准 MIME',
    size_bytes        BIGINT       NOT NULL COMMENT '文件真实字节数',
    sha256            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                                  NOT NULL COMMENT '文件内容 SHA-256',
    status            VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED'
                                  COMMENT 'UPLOADED/PROCESSING/READY/FAILED',
    failure_reason    VARCHAR(500) NULL COMMENT '处理失败原因；不得保存堆栈或密钥',
    chunk_count       INT          NOT NULL DEFAULT 0 COMMENT '成功生成的分片数量',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_ai_document_object (object_key),
    UNIQUE KEY uk_merchant_ai_document_content (merchant_id, sha256),
    KEY idx_merchant_ai_document_assistant_time
        (assistant_id, created_at, id),
    KEY idx_merchant_ai_document_merchant_status
        (merchant_id, status),

    CONSTRAINT fk_merchant_ai_document_assistant
        FOREIGN KEY (assistant_id) REFERENCES merchant_ai_assistant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_ai_document_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_merchant_ai_document_type
        CHECK (file_type IN ('PDF', 'DOCX', 'TXT', 'MARKDOWN')),
    CONSTRAINT ck_merchant_ai_document_status
        CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_merchant_ai_document_size
        CHECK (size_bytes > 0),
    CONSTRAINT ck_merchant_ai_document_chunk_count
        CHECK (chunk_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '商家智能导购文档元数据';
