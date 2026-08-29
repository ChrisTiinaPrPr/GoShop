package org.example.goshop.merchant.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiQuestionRequest;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiStreamEvent;
import org.example.goshop.merchant.ai.service.BuyerMerchantAiAnswerService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** 买家从指定店铺页面使用商家智能导购的入口。 */
@Tag(name = "买家智能导购")
@Validated
@RestController
@RequestMapping("/api/v1/buyer/merchants/{merchantId}/ai-assistant")
@RequiredArgsConstructor
public class BuyerMerchantAiController {

    private final BuyerMerchantAiAnswerService answerService;

    /**
     * merchantId 固定在店铺路径中，表示买家只能从当前店铺上下文提问；
     * 请求体不接受 merchantId 或 assistantId，避免跨店切换知识空间。
     */
    @Operation(
            summary = "向当前店铺的智能导购提问",
            description = "使用 text/event-stream 增量返回模型文本和最终引用"
    )
    @PostMapping(
            value = "/questions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<Flux<BuyerMerchantAiStreamEvent>> askQuestion(
            Authentication authentication,
            @PathVariable
            @Positive(message = "商家ID必须为正数")
            Long merchantId,
            @Valid @RequestBody BuyerMerchantAiQuestionRequest request
    ) {
        Long buyerUserId = (Long) authentication.getPrincipal();
        Flux<BuyerMerchantAiStreamEvent> events =
                answerService.streamAnswer(
                        buyerUserId,
                        merchantId,
                        request
                );

        /*
         * no-cache 防止中间缓存保存某次用户回答；X-Accel-Buffering=no
         * 告知常见 Nginx 部署不要攒满缓冲区后再一次性转发。Spring MVC
         * 会按 text/event-stream 对 Flux 中的每个对象立即编码并刷新。
         */
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(events);
    }
}
