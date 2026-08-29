package org.example.goshop.merchant.ai.dto;

/** 买家店铺智能导购 SSE 事件类型。 */
public enum BuyerMerchantAiStreamEventType {
    /** 已完成店铺、助手和知识空间校验，可以初始化回答气泡。 */
    STARTED,
    /** 模型生成的一段增量文本，客户端必须按收到顺序追加。 */
    TEXT_DELTA,
    /** 回答生成完成，并携带最终的依据状态与引用列表。 */
    COMPLETED,
    /** SSE 已建立后发生可恢复错误，客户端应停止本次流式展示。 */
    ERROR
}
