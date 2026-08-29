-- Agent 结构化结果卡片增量迁移。
-- 前置条件：已执行 agent-migration.sql，agent_message 表已经存在。
-- 执行次数：仅执行一次；应用包含结果卡片持久化代码前必须完成本迁移。

ALTER TABLE agent_message
    ADD COLUMN result_cards_json JSON NULL
        COMMENT '服务端生成的安全商品/订单结果卡片数组，不含地址、手机号和工具原始结果'
        AFTER run_id;
