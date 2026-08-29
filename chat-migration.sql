-- 联系商家 / 站内聊天数据库迁移脚本
-- 适用版本：MySQL 8.0.16+（使用 CHECK 约束），执行一次。
-- 前置条件：sys_user、merchant、mall_order 表已经存在，且存储引擎为 InnoDB。
-- 说明：项目使用 MyBatis-Plus ASSIGN_ID，因此聊天会话和消息 ID 均由应用生成，不使用 AUTO_INCREMENT。

-- 会话表：一个买家与一个商家之间最多只有一个会话。
CREATE TABLE chat_conversation (
    id BIGINT NOT NULL COMMENT '会话 ID，由应用生成',
    buyer_user_id BIGINT NOT NULL COMMENT '买家 sys_user.id',
    merchant_id BIGINT NOT NULL COMMENT '商家 merchant.id',

    -- 最近消息字段用于会话列表排序和摘要查询；空会话的两个字段必须同时为 NULL。
    last_message_id BIGINT NULL COMMENT '本会话最后一条消息 ID',
    last_message_at DATETIME(3) NULL COMMENT '本会话最后一条消息发送时间',

    -- 已读游标保存双方最后读到的消息。NULL 表示尚未读过对方消息。
    buyer_last_read_message_id BIGINT NULL COMMENT '买家最后已读消息 ID',
    merchant_last_read_message_id BIGINT NULL COMMENT '商家最后已读消息 ID',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),

    -- 防止重复点击“联系商家”产生多个会话；并发创建时由唯一键兜底。
    UNIQUE KEY uk_chat_conversation_buyer_merchant (buyer_user_id, merchant_id),

    -- 支持买家端和商家端分别按最近消息时间倒序加载会话列表。
    KEY idx_chat_conversation_buyer_last (buyer_user_id, last_message_at, id),
    KEY idx_chat_conversation_merchant_last (merchant_id, last_message_at, id),

    CONSTRAINT fk_chat_conversation_buyer
        FOREIGN KEY (buyer_user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_chat_conversation_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_chat_conversation_last_message
        CHECK (
            (last_message_id IS NULL AND last_message_at IS NULL)
            OR
            (last_message_id IS NOT NULL AND last_message_at IS NOT NULL)
        )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='买家与商家的一对一聊天会话';


-- 消息表：MySQL 是聊天记录的事实来源，WebSocket 只负责实时推送。
CREATE TABLE chat_message (
    id BIGINT NOT NULL COMMENT '消息 ID，由应用生成并作为游标',
    conversation_id BIGINT NOT NULL COMMENT '所属会话 ID',
    sender_user_id BIGINT NOT NULL COMMENT '发送者 sys_user.id；商家发送时仍保存其账号 ID',
    sender_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送门户：USER 或 MERCHANT',
    message_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消息类型：TEXT、IMAGE 或 ORDER',

    -- 三类消息共用一张表，但 CHECK 约束保证只有当前消息类型的载荷字段有值。
    text_content VARCHAR(2000) NULL COMMENT '纯文本消息内容，前端必须按文本转义展示',
    image_object_key VARCHAR(500) NULL COMMENT '私有 OSS 对象键；禁止持久化临时签名 URL',
    image_meta_json JSON NULL COMMENT '图片 MIME、字节数、宽高等非敏感元数据',
    order_id BIGINT NULL COMMENT '订单卡片关联的 mall_order.id',

    -- 由客户端生成 UUID；同一发送账号重试时命中唯一键，避免重复消息和重复未读数。
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端幂等 UUID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务端落库时间',

    PRIMARY KEY (id),

    -- 该复合唯一键既服务 before/afterMessageId 游标查询，也供会话表的复合外键引用，
    -- 从数据库层保证最近消息和已读游标只能指向同一个会话内的消息。
    UNIQUE KEY uk_chat_message_conversation_cursor (conversation_id, id),
    UNIQUE KEY uk_chat_message_sender_client (sender_user_id, sender_role, client_message_id),
    KEY idx_chat_message_order (order_id),

    CONSTRAINT fk_chat_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_chat_message_sender
        FOREIGN KEY (sender_user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_chat_message_order
        FOREIGN KEY (order_id) REFERENCES mall_order (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_chat_message_sender_role
        CHECK (sender_role IN ('USER', 'MERCHANT')),
    CONSTRAINT ck_chat_message_type
        CHECK (message_type IN ('TEXT', 'IMAGE', 'ORDER')),

    -- TEXT：只允许文本载荷，去除首尾空白后长度为 1～2000 个字符。
    -- IMAGE：只保存私有 OSS 对象键与可选元数据。
    -- ORDER：只保存订单外键，订单是否同时属于会话买家和商家由 Service 在事务内校验。
    CONSTRAINT ck_chat_message_payload
        CHECK (
            (
                message_type = 'TEXT'
                AND text_content IS NOT NULL
                AND CHAR_LENGTH(TRIM(text_content)) BETWEEN 1 AND 2000
                AND image_object_key IS NULL
                AND image_meta_json IS NULL
                AND order_id IS NULL
            )
            OR
            (
                message_type = 'IMAGE'
                AND text_content IS NULL
                AND image_object_key IS NOT NULL
                AND CHAR_LENGTH(TRIM(image_object_key)) > 0
                AND order_id IS NULL
                AND image_meta_json IS NOT NULL
            )
            OR
            (
                message_type = 'ORDER'
                AND text_content IS NULL
                AND image_object_key IS NULL
                AND image_meta_json IS NULL
                AND order_id IS NOT NULL
            )
        )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='买家与商家的聊天消息';


-- 消息表创建后再补充会话游标外键，解决 conversation/message 的循环依赖。
-- 使用 (会话 ID, 消息 ID) 复合外键，而不是只引用消息 ID，避免游标误指向其他会话。
ALTER TABLE chat_conversation
    ADD CONSTRAINT fk_chat_conversation_last_message
        FOREIGN KEY (id, last_message_id)
        REFERENCES chat_message (conversation_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_chat_conversation_buyer_read
        FOREIGN KEY (id, buyer_last_read_message_id)
        REFERENCES chat_message (conversation_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_chat_conversation_merchant_read
        FOREIGN KEY (id, merchant_last_read_message_id)
        REFERENCES chat_message (conversation_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 数据库无法通过普通 CHECK/FOREIGN KEY 表达的跨表业务规则，必须由聊天 Service 在事务内保证：
-- 1. sender_role=USER 时，sender_user_id 必须等于会话 buyer_user_id；
-- 2. sender_role=MERCHANT 时，sender_user_id 必须等于会话商家的 merchant.user_id；
-- 3. ORDER 消息关联订单的 user_id、merchant_id 必须与会话参与者完全一致；
-- 4. last_message_id 和双方 last_read_message_id 只允许单调递增，不能被旧请求回退。
