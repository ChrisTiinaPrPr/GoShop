package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeMatchResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchRequest;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchResponse;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.entity.MerchantAiKnowledgeChunkRow;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentChunkMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.service.MerchantService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商家智能导购知识库语义检索服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAiKnowledgeSearchService {

    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MIN_CANDIDATES = 12;
    private static final int MAX_CANDIDATES = 30;
    private static final double CANDIDATE_SIMILARITY_THRESHOLD = 0D;
    private static final String QUERY_INSTRUCTION =
            "Instruct: Given a buyer shopping question, retrieve relevant "
                    + "product knowledge from the current merchant that "
                    + "answers the question.\nQuery: ";

    private final MerchantService merchantService;
    private final MerchantAiAssistantMapper assistantMapper;
    private final MerchantAiDocumentChunkMapper chunkMapper;
    private final MerchantAiRagAvailabilityService availabilityService;
    private final MerchantAiKnowledgeReranker knowledgeReranker;

    /**
     * 检索当前登录商家自己的 READY 知识分片。
     *
     * <p>安全链路分为两层：Qdrant 查询先使用 merchant_id 与 assistant_id
     * 组合过滤，减少无关候选；随后 MySQL 联表再次验证租户归属和 READY
     * 状态，并以数据库正文覆盖向量载荷。候选数按请求 topK 的三倍获取，
     * 为删除延迟产生的无效向量预留过滤空间，最终结果仍不超过请求数量。</p>
     */
    @Transactional(readOnly = true)
    public MerchantAiKnowledgeSearchResponse searchCurrentKnowledge(
            Long merchantUserId,
            MerchantAiKnowledgeSearchRequest request
    ) {
        MerchantProfileResponse merchant =
                merchantService.getCurrentMerchantProfile(
                        merchantUserId
                );
        MerchantAiAssistant assistant =
                assistantMapper.selectByMerchantId(merchant.id());
        if (assistant == null) {
            throw new BusinessException(
                    40901,
                    "请先保存智能导购助手配置"
            );
        }

        return searchKnowledge(
                merchant.id(),
                assistant,
                request
        );
    }

    /**
     * 为买家恢复一个已经启用的店铺助手，并检索该助手自己的知识库。
     *
     * <p>商家状态、助手开关和向量租户字段都由服务端校验。买家请求只
     * 提供 URL 中的 merchantId，不能提交 assistantId 来切换向量空间。</p>
     */
    @Transactional(readOnly = true)
    public EnabledStoreKnowledge searchEnabledStoreKnowledge(
            Long merchantId,
            MerchantAiKnowledgeSearchRequest request
    ) {
        Merchant merchant = merchantService.requireEnabledMerchant(
                merchantId
        );
        MerchantAiAssistant assistant =
                assistantMapper.selectByMerchantId(merchantId);
        if (assistant == null
                || !Integer.valueOf(1).equals(assistant.getEnabled())) {
            /* 未配置与已关闭返回相同结果，避免公开接口泄露商家配置状态。 */
            throw new BusinessException(
                    40401,
                    "该店铺暂未启用智能导购"
            );
        }

        MerchantAiKnowledgeSearchResponse knowledge = searchKnowledge(
                merchantId,
                assistant,
                request
        );
        return new EnabledStoreKnowledge(
                merchant,
                assistant,
                knowledge
        );
    }

    /** 商家测试入口与买家回答入口共用同一条租户隔离检索链路。 */
    private MerchantAiKnowledgeSearchResponse searchKnowledge(
            Long merchantId,
            MerchantAiAssistant assistant,
            MerchantAiKnowledgeSearchRequest request
    ) {

        String query = request.normalizedQuery();
        int topK = request.effectiveTopK();
        double threshold =
                request.effectiveSimilarityThreshold();
        /*
         * Qwen3 Embedding 的宽泛购物需求分数可能明显低于精确型号问题。
         * 例如“FPS 游戏鼠标”与正确商品分片只有约 0.38，而手机壳等无关
         * 问题也可能让通用说明达到约 0.44，因此不能在 Qdrant 入口直接用
         * 业务阈值裁剪。这里取得至少 12 个低门槛候选，再由租户复核和本地
         * 词法重排执行最终相关性判断；候选总数仍限制为 30，避免放大开销。
         */
        int candidateLimit = Math.min(
                Math.max(
                        topK * CANDIDATE_MULTIPLIER,
                        MIN_CANDIDATES
                ),
                MAX_CANDIDATES
        );

        VectorStore vectorStore =
                availabilityService.requireVectorStore();
        List<Document> candidates;
        try {
            candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            /* Qwen3 推荐只在查询侧增加检索任务指令。 */
                            .query(retrievalQuery(query))
                            .topK(candidateLimit)
                            .similarityThreshold(
                                    CANDIDATE_SIMILARITY_THRESHOLD
                            )
                            .filterExpression(tenantFilter(
                                    merchantId,
                                    assistant.getId()
                            ))
                            .build()
            );
        } catch (Exception exception) {
            log.error(
                    "商家知识库检索失败，merchantId={}, assistantId={}",
                    merchantId,
                    assistant.getId(),
                    exception
            );
            throw new BusinessException(
                    50301,
                    "智能导购知识库检索暂时不可用，请稍后重试"
            );
        }

        if (candidates == null || candidates.isEmpty()) {
            return response(query, topK, threshold, List.of());
        }

        /* LinkedHashMap 去重时保留 Qdrant 的相似度排序。 */
        Map<String, Document> rankedCandidates = new LinkedHashMap<>();
        for (Document candidate : candidates) {
            rankedCandidates.putIfAbsent(candidate.getId(), candidate);
        }
        List<MerchantAiKnowledgeChunkRow> readyChunks =
                chunkMapper.selectReadyOwnedChunks(
                        merchantId,
                        assistant.getId(),
                        List.copyOf(rankedCandidates.keySet())
                );
        Map<String, MerchantAiKnowledgeChunkRow> chunkByVectorId =
                new LinkedHashMap<>();
        for (MerchantAiKnowledgeChunkRow chunk : readyChunks) {
            chunkByVectorId.put(chunk.getVectorId(), chunk);
        }

        List<MerchantAiKnowledgeMatchResponse> matches =
                knowledgeReranker.rerank(
                                query,
                                rankedCandidates,
                                chunkByVectorId
                        )
                        .stream()
                        .filter(ranked -> knowledgeReranker.isRelevant(
                                ranked,
                                threshold
                        ))
                        .limit(topK)
                        .map(ranked -> toResponse(
                                ranked.candidate(),
                                ranked.chunk()
                        ))
                        .toList();

        return response(query, topK, threshold, matches);
    }

    /**
     * 查询指令仅参与查询向量生成，响应和本地词法重排仍使用买家的原始问题，
     * 防止英文指令词污染型号、中文双字词等本地相关性信号。
     */
    private String retrievalQuery(String query) {
        return QUERY_INSTRUCTION + query;
    }

    /** 买家回答生成所需的启用店铺、助手配置和已复核知识。 */
    public record EnabledStoreKnowledge(
            Merchant merchant,
            MerchantAiAssistant assistant,
            MerchantAiKnowledgeSearchResponse knowledge
    ) {
    }

    /** Qdrant 侧使用字符串 ID，与文档入库时写入的 metadata 类型保持一致。 */
    private Filter.Expression tenantFilter(
            Long merchantId,
            Long assistantId
    ) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("merchant_id"),
                        new Filter.Value(merchantId.toString())
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("assistant_id"),
                        new Filter.Value(assistantId.toString())
                )
        );
    }

    private MerchantAiKnowledgeMatchResponse toResponse(
            Document candidate,
            MerchantAiKnowledgeChunkRow chunk
    ) {
        return new MerchantAiKnowledgeMatchResponse(
                chunk.getDocumentId(),
                chunk.getOriginalFilename(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                candidate.getScore()
        );
    }

    private MerchantAiKnowledgeSearchResponse response(
            String query,
            int topK,
            double threshold,
            List<MerchantAiKnowledgeMatchResponse> matches
    ) {
        return new MerchantAiKnowledgeSearchResponse(
                query,
                topK,
                threshold,
                matches.size(),
                List.copyOf(matches)
        );
    }
}
