package org.example.goshop.agent.entity;

/**
 * Agent 工具调用的执行状态。
 *
 * <p>工具是模型读取商城事实的唯一受控入口。
 * 商品价格、库存和订单状态都必须来自工具结果。</p>
 */
public enum AgentToolCallStatus {

    /** 已收到模型的工具调用请求，工具正在执行。 */
    RUNNING,

    /** 工具执行成功，并向模型返回了脱敏结果。 */
    SUCCEEDED,

    /**
     * 工具执行失败。
     *
     * <p>失败原因只能保存脱敏分类，不能把数据库异常堆栈、
     * SQL、用户隐私或完整订单写入审计记录。</p>
     */
    FAILED
}
