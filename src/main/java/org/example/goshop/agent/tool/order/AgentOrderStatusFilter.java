package org.example.goshop.agent.tool.order;

/**
 * 模型允许使用的订单状态筛选条件。
 *
 * <p>枚举会进入工具 JSON Schema，模型不能产生任意 SQL 状态值。</p>
 */
public enum AgentOrderStatusFilter {

    /**
     * 查询所有状态。
     */
    ALL,

    PENDING_PAYMENT,
    WAITING_SHIPMENT,
    WAITING_RECEIPT,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    REFUNDED;

    /**
     * 转换成订单业务 Service 使用的状态。
     *
     * @return ALL 返回 null，其他状态返回枚举名称
     */
    public String businessValue() {
        return this == ALL
                ? null
                : name();
    }
}
