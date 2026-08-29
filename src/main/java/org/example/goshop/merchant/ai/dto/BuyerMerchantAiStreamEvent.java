package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 买家店铺智能导购的单次 SSE 事件。
 *
 * <p>不同事件只填写各自需要的字段：STARTED 提供助手资料，TEXT_DELTA
 * 提供文本增量，COMPLETED 提供引用，ERROR 提供稳定错误码和安全消息。
 * 采用同一个扁平 DTO 可以让前端只维护一套事件解析器，同时避免在结束
 * 事件中再次传输完整答案。</p>
 */
@Schema(
        name = "BuyerMerchantAiStreamEvent",
        description = "店铺智能导购 text/event-stream 中的单个事件"
)
public record BuyerMerchantAiStreamEvent(
        BuyerMerchantAiStreamEventType type,
        Long merchantId,
        Long assistantId,
        String assistantName,
        String assistantAvatarUrl,
        String question,
        String delta,
        Boolean grounded,
        List<BuyerMerchantAiCitationResponse> citations,
        Integer code,
        String message
) {

    public BuyerMerchantAiStreamEvent {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 创建流首事件；此时尚未向客户端承诺模型一定能够完成回答。 */
    public static BuyerMerchantAiStreamEvent started(
            Long merchantId,
            Long assistantId,
            String assistantName,
            String assistantAvatarUrl,
            String question
    ) {
        return new BuyerMerchantAiStreamEvent(
                BuyerMerchantAiStreamEventType.STARTED,
                merchantId,
                assistantId,
                assistantName,
                assistantAvatarUrl,
                question,
                null,
                null,
                List.of(),
                null,
                null
        );
    }

    /** 创建模型文本增量；空格和换行也是有效分片，不能在这里 strip。 */
    public static BuyerMerchantAiStreamEvent textDelta(String delta) {
        return new BuyerMerchantAiStreamEvent(
                BuyerMerchantAiStreamEventType.TEXT_DELTA,
                null,
                null,
                null,
                null,
                null,
                delta,
                null,
                List.of(),
                null,
                null
        );
    }

    /** 创建正常结束事件；完整答案已经由此前的 TEXT_DELTA 顺序组成。 */
    public static BuyerMerchantAiStreamEvent completed(
            boolean grounded,
            List<BuyerMerchantAiCitationResponse> citations
    ) {
        return new BuyerMerchantAiStreamEvent(
                BuyerMerchantAiStreamEventType.COMPLETED,
                null,
                null,
                null,
                null,
                null,
                null,
                grounded,
                citations,
                null,
                null
        );
    }

    /** 创建流内错误事件，避免响应开始后只能无提示地断开连接。 */
    public static BuyerMerchantAiStreamEvent error(
            int code,
            String message
    ) {
        return new BuyerMerchantAiStreamEvent(
                BuyerMerchantAiStreamEventType.ERROR,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                code,
                message
        );
    }
}
