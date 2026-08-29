package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiCitationResponse;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiQuestionRequest;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiStreamEvent;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeMatchResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchRequest;
import org.example.goshop.merchant.ai.service.MerchantAiKnowledgeSearchService.EnabledStoreKnowledge;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.service.ProductService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 编排买家店铺内的知识检索、实时商品查询、回答生成和引用返回。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerMerchantAiAnswerService {

    private static final int KNOWLEDGE_TOP_K = 4;
    private static final int PRODUCT_SNAPSHOT_LIMIT = 20;
    private static final int MAX_CITATION_EXCERPT_CHARS = 260;

    private final MerchantAiKnowledgeSearchService knowledgeSearchService;
    private final ProductService productService;
    private final MerchantAiAnswerModelClient answerModelClient;
    private final BuyerMerchantAiRateLimitService rateLimitService;

    /**
     * 生成一次不持久化历史的店铺内导购回答。
     *
     * <p>merchantId 来自当前店铺 URL，buyerUserId 来自 USER JWT。服务端
     * 重新校验启用店铺和启用助手；相关知识为空时直接返回确定性兜底，
     * 不把无证据问题发送给模型。后续多轮会话可以在本接口之上增加独立
     * conversationId，而不放宽本次店铺隔离边界。</p>
     */
    public Flux<BuyerMerchantAiStreamEvent> streamAnswer(
            Long buyerUserId,
            Long merchantId,
            BuyerMerchantAiQuestionRequest request
    ) {
        if (buyerUserId == null || buyerUserId <= 0) {
            throw new BusinessException(40101, "请先登录买家账号");
        }
        /* 在 Embedding 和模型调用之前消耗额度，拒绝请求不产生外部费用。 */
        rateLimitService.checkAllowed(buyerUserId, merchantId);
        String question = request.normalizedQuestion();
        EnabledStoreKnowledge context =
                knowledgeSearchService.searchEnabledStoreKnowledge(
                        merchantId,
                        new MerchantAiKnowledgeSearchRequest(
                                question,
                                KNOWLEDGE_TOP_K,
                                null
                        )
                );
        List<MerchantAiKnowledgeMatchResponse> matches =
                context.knowledge().matches();
        String avatarUrl = context.assistant().getAvatarUrl() == null
                || context.assistant().getAvatarUrl().isBlank()
                ? context.merchant().getLogoUrl()
                : context.assistant().getAvatarUrl();

        BuyerMerchantAiStreamEvent started =
                BuyerMerchantAiStreamEvent.started(
                        context.merchant().getId(),
                        context.assistant().getId(),
                        context.assistant().getName(),
                        avatarUrl,
                        question
                );

        if (matches.isEmpty()) {
            String fallback = "抱歉，根据该店铺当前提供的导购资料，暂时无法确认这个问题。"
                    + "您可以换个商品相关的问法，或联系店铺人工客服。";
            /*
             * 无证据回答不调用模型，但仍遵守同一 SSE 协议。这样前端无需
             * 回退到 JSON 分支，也能按 STARTED -> TEXT_DELTA -> COMPLETED
             * 的稳定顺序处理所有成功请求。
             */
            return Flux.just(
                    started,
                    BuyerMerchantAiStreamEvent.textDelta(fallback),
                    BuyerMerchantAiStreamEvent.completed(false, List.of())
            );
        }

        /*
         * 公开商品 SQL 会限制当前 merchant_id、上架 SPU 和启用 SKU。
         * 快照只提供当前最低价和可售商品集合，文档价格不能覆盖它。
         */
        PageResult<ProductListResponse> productSnapshot =
                productService.listPublicMerchantProducts(
                        merchantId,
                        1,
                        PRODUCT_SNAPSHOT_LIMIT,
                        null,
                        null,
                        "sales"
                );
        List<BuyerMerchantAiCitationResponse> citations = citations(matches);
        AtomicBoolean hasMeaningfulText = new AtomicBoolean(false);
        Flux<BuyerMerchantAiStreamEvent> textEvents = answerModelClient.stream(
                context.merchant(),
                context.assistant(),
                question,
                matches,
                productSnapshot.records(),
                productSnapshot.total()
        ).map(delta -> {
            if (StringUtils.hasText(delta)) {
                hasMeaningfulText.set(true);
            }
            return BuyerMerchantAiStreamEvent.textDelta(delta);
        });

        /*
         * COMPLETED 必须等模型流自然结束后再生成。若供应商返回空流，则把
         * 它转成明确错误事件，不能让页面把空白气泡误判为一次成功回答。
         */
        Mono<BuyerMerchantAiStreamEvent> completedEvent = Mono.defer(() -> {
            if (!hasMeaningfulText.get()) {
                return Mono.error(new BusinessException(
                        50301,
                        "智能导购回答生成暂时不可用，请稍后重试"
                ));
            }
            return Mono.just(
                    BuyerMerchantAiStreamEvent.completed(true, citations)
            );
        });

        return Flux.concat(
                        Flux.just(started),
                        textEvents,
                        completedEvent
                )
                /*
                 * SSE 响应一旦写出 STARTED 就无法再修改 HTTP 状态码，后续
                 * 模型异常必须作为 ERROR 事件传给浏览器，不能只断开连接。
                 */
                .onErrorResume(exception -> {
                    BusinessException businessException =
                            exception instanceof BusinessException business
                                    ? business
                                    : new BusinessException(
                                            50301,
                                            "智能导购回答生成暂时不可用，请稍后重试"
                                    );
                    log.warn(
                            "店铺智能导购流式回答中止，merchantId={}, code={}",
                            merchantId,
                            businessException.getCode()
                    );
                    return Flux.just(BuyerMerchantAiStreamEvent.error(
                            businessException.getCode(),
                            businessException.getMessage()
                    ));
                });
    }

    private List<BuyerMerchantAiCitationResponse> citations(
            List<MerchantAiKnowledgeMatchResponse> matches
    ) {
        List<BuyerMerchantAiCitationResponse> citations =
                new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            MerchantAiKnowledgeMatchResponse match = matches.get(index);
            citations.add(new BuyerMerchantAiCitationResponse(
                    index + 1,
                    match.documentId(),
                    match.originalFilename(),
                    match.chunkIndex(),
                    excerpt(match.content()),
                    match.score()
            ));
        }
        return List.copyOf(citations);
    }

    /** 返回单行、有限长度摘要，避免响应再次携带整份长分片。 */
    private String excerpt(String content) {
        String normalized = content == null
                ? ""
                : content.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= MAX_CITATION_EXCERPT_CHARS) {
            return normalized;
        }
        return normalized.substring(
                0,
                MAX_CITATION_EXCERPT_CHARS
        ) + "…";
    }
}
