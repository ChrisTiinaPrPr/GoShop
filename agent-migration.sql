-- 买家购物 Agent 数据库迁移脚本。
-- 适用：MySQL 8.0.16+（脚本使用 CHECK 约束和生成列）。
-- 执行次数：仅执行一次；执行前请先备份数据库。
-- 前置表：sys_user、product_sku 等商城基础表已经存在，且存储引擎为 InnoDB。
-- 主键策略：与项目现有实体一致，所有 BIGINT 主键由 MyBatis-Plus ASSIGN_ID 生成。


-- ============================================================
-- 1. Agent 会话
--
-- 这是买家在 /assistant 中看到的会话列表。
-- title 由第一条用户消息截取生成，不需要额外调用模型。
-- last_message_id 是列表查询的冗余游标，由 Service 在事务中维护；
-- 为避免 conversation/message 循环外键妨碍删除，这里不为它创建外键。
-- ============================================================
CREATE TABLE agent_conversation
(
    id              BIGINT       NOT NULL COMMENT '会话 ID，由应用生成',
    user_id         BIGINT       NOT NULL COMMENT '所属买家 sys_user.id',
    title           VARCHAR(100) NOT NULL DEFAULT '新会话' COMMENT '会话标题，最多展示首条消息前 30 个字符',
    last_message_id BIGINT       NULL COMMENT '最后一条已落库消息 ID，由 Service 维护',
    last_message_at DATETIME(3)  NULL COMMENT '最后一条消息完成时间',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_agent_conversation_user_last
        (user_id, last_message_at, id),

    CONSTRAINT fk_agent_conversation_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    -- 空会话允许两个字段同时为 NULL；有消息后必须同时有值。
    CONSTRAINT ck_agent_conversation_last_message
        CHECK (
            (last_message_id IS NULL AND last_message_at IS NULL)
            OR
            (last_message_id IS NOT NULL AND last_message_at IS NOT NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '买家购物 Agent 会话';


-- ============================================================
-- 2. Agent 可见消息
--
-- 只保存用户和助手最终可见的消息，不把工具调用过程伪装成聊天消息。
-- USER 消息创建后就是 COMPLETED；ASSISTANT 消息生成时先为 STREAMING，
-- 完整结束后更新为 COMPLETED，失败则更新为 FAILED。
-- client_message_id 只属于 USER 消息，用于浏览器重试幂等。
-- ============================================================
CREATE TABLE agent_message
(
    id                BIGINT       NOT NULL COMMENT '消息 ID，由应用生成，同时作为分页游标',
    conversation_id   BIGINT       NOT NULL COMMENT '所属 Agent 会话',
    role              VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'USER 或 ASSISTANT',
    content           TEXT         NOT NULL COMMENT '纯文本消息；前端必须转义 HTML',
    status            VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'STREAMING、COMPLETED 或 FAILED',
    client_message_id CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '用户消息幂等 UUID；助手消息必须为空',
    run_id            BIGINT       NULL COMMENT '助手消息所属运行 ID；为避免循环依赖不创建外键',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at      DATETIME(3)  NULL COMMENT '助手消息完成或失败时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_message_conversation_client
        (conversation_id, client_message_id),
    KEY idx_agent_message_conversation_cursor
        (conversation_id, id),
    KEY idx_agent_message_run (run_id),

    CONSTRAINT fk_agent_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES agent_conversation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_agent_message_role
        CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_agent_message_status
        CHECK (status IN ('STREAMING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_agent_message_client_id
        CHECK (
            (role = 'USER' AND client_message_id IS NOT NULL AND status = 'COMPLETED')
            OR
            (role = 'ASSISTANT' AND client_message_id IS NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '买家与购物 Agent 的可见消息';


-- ============================================================
-- 3. Agent 运行
--
-- 一条用户消息对应一次模型运行和一条助手占位消息。
-- active_conversation_id 是生成列：只有 RUNNING 状态才产生会话 ID，
-- 唯一索引因此可以保证同一会话最多只有一个正在运行的请求；
-- 其他状态生成 NULL，而 MySQL 唯一索引允许存在多个 NULL。
-- ============================================================
CREATE TABLE agent_run
(
    id                     BIGINT        NOT NULL COMMENT '运行 ID，由应用生成',
    conversation_id        BIGINT        NOT NULL COMMENT '所属会话',
    user_message_id        BIGINT        NOT NULL COMMENT '触发本次运行的 USER 消息',
    assistant_message_id   BIGINT        NOT NULL COMMENT '本次运行写入的 ASSISTANT 消息',
    provider               VARCHAR(50)   NOT NULL COMMENT '模型供应商标识，例如 openai-compatible',
    model                  VARCHAR(100)  NOT NULL COMMENT '实际模型名',
    status                 VARCHAR(16)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'RUNNING、COMPLETED、FAILED 或 TIMED_OUT',
    prompt_tokens          INT           NULL COMMENT '输入 Token；供应商未返回时为空',
    completion_tokens      INT           NULL COMMENT '输出 Token；供应商未返回时为空',
    first_token_ms         INT           NULL COMMENT '首个文本增量延迟，单位毫秒',
    duration_ms            INT           NULL COMMENT '运行总耗时，单位毫秒',
    error_code             VARCHAR(50)   NULL COMMENT '脱敏后的内部错误分类，不保存异常堆栈',
    created_at             DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at            DATETIME(3)   NULL,

    active_conversation_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'RUNNING' THEN conversation_id ELSE NULL END) STORED,

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_user_message (user_message_id),
    UNIQUE KEY uk_agent_run_assistant_message (assistant_message_id),
    UNIQUE KEY uk_agent_run_active_conversation (active_conversation_id),
    KEY idx_agent_run_conversation_created (conversation_id, created_at, id),

    CONSTRAINT fk_agent_run_conversation
        FOREIGN KEY (conversation_id) REFERENCES agent_conversation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_user_message
        FOREIGN KEY (user_message_id) REFERENCES agent_message (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_assistant_message
        FOREIGN KEY (assistant_message_id) REFERENCES agent_message (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_agent_run_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT ck_agent_run_tokens
        CHECK (
            (prompt_tokens IS NULL OR prompt_tokens >= 0)
            AND
            (completion_tokens IS NULL OR completion_tokens >= 0)
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '一次 Agent 模型运行及其可观测数据';


-- ============================================================
-- 4. Agent 工具调用审计
--
-- 只保存脱敏、截断后的参数和结果摘要。
-- 禁止保存用户地址、手机号、API Key、系统提示词或完整订单 JSON。
-- 首期使用服务端生成的 UUID 作为 tool_call_id，用于把审计记录与开始/完成事件对应起来。
-- Spring AI 自动工具执行不会把供应商 toolCallId 注入 @Tool 方法；未来改为手动工具循环后可替换为供应商 ID。
-- ============================================================
CREATE TABLE agent_tool_call
(
    id                    BIGINT       NOT NULL COMMENT '工具调用记录 ID，由应用生成',
    run_id                BIGINT       NOT NULL COMMENT '所属 Agent 运行',
    tool_call_id          VARCHAR(100) NOT NULL COMMENT '工具调用关联 ID；首期由服务端生成 UUID',
    tool_name             VARCHAR(100) NOT NULL COMMENT '白名单工具名',
    arguments_summary_json JSON        NULL COMMENT '脱敏且截断的参数摘要',
    result_summary_json    JSON        NULL COMMENT '脱敏且截断的结果摘要',
    status                VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'RUNNING、SUCCEEDED 或 FAILED',
    duration_ms           INT          NULL COMMENT '工具耗时，单位毫秒',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at           DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_call_run_call (run_id, tool_call_id),
    KEY idx_agent_tool_call_run_created (run_id, created_at, id),

    CONSTRAINT fk_agent_tool_call_run
        FOREIGN KEY (run_id) REFERENCES agent_run (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_agent_tool_call_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_agent_tool_call_duration
        CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent 白名单工具调用审计';


-- ============================================================
-- 5. 用户待确认动作
--
-- 首期 action_type 只允许 ADD_CART_ITEM。
-- payload_json 由服务端生成并保存 skuId、quantity 和展示快照；
-- confirm 请求只提交 actionId，不能让前端替换 SKU 或数量。
-- idempotency_key 在创建 PENDING 动作时为空，在首次确认时写入。
-- ============================================================
CREATE TABLE agent_action
(
    id              BIGINT       NOT NULL COMMENT '动作 ID，由应用生成',
    conversation_id BIGINT       NOT NULL COMMENT '动作来源会话',
    user_id         BIGINT       NOT NULL COMMENT '动作所属买家',
    action_type     VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '首期仅 ADD_CART_ITEM',
    payload_json    JSON         NOT NULL COMMENT '服务端生成的动作载荷和展示快照',
    status          VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING、CONFIRMED、CANCELLED 或 EXPIRED',
    idempotency_key VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '首次确认时写入的幂等键',
    result_json     JSON         NULL COMMENT '确认成功后的 CartItemResponse 摘要',
    expires_at      DATETIME(3)  NOT NULL COMMENT '默认创建时间后 10 分钟',
    executed_at     DATETIME(3)  NULL COMMENT '确认成功时间',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_action_user_idempotency (user_id, idempotency_key),
    KEY idx_agent_action_conversation_created (conversation_id, created_at, id),
    KEY idx_agent_action_expire (status, expires_at),

    CONSTRAINT fk_agent_action_conversation
        FOREIGN KEY (conversation_id) REFERENCES agent_conversation (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_agent_action_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CONSTRAINT ck_agent_action_type
        CHECK (action_type IN ('ADD_CART_ITEM')),
    CONSTRAINT ck_agent_action_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_agent_action_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_agent_action_execution
        CHECK (
            (status = 'CONFIRMED' AND idempotency_key IS NOT NULL AND executed_at IS NOT NULL)
            OR
            (status <> 'CONFIRMED' AND executed_at IS NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent 生成且必须由用户确认的业务动作';


-- ============================================================
-- Service 层必须额外保证、普通外键/CHECK 无法表达的规则
-- ============================================================
-- 1. agent_conversation.user_id 必须等于当前 JWT 中的买家 userId。
-- 2. run 的两条消息必须属于同一个 conversation，角色分别为 USER、ASSISTANT。
-- 3. agent_message.run_id 只能指向生成该助手消息的 run。
-- 4. last_message_id 必须属于当前 conversation，且只允许向更大的消息 ID 推进。
-- 5. agent_action.user_id 必须与 conversation.user_id 一致。
-- 6. 确认动作时必须锁定动作行（SELECT ... FOR UPDATE），避免并发重复加购。
-- 7. 删除会话前必须确认没有 RUNNING 运行，然后按以下顺序删除：
--    agent_action -> agent_tool_call -> agent_run -> agent_message -> agent_conversation。
