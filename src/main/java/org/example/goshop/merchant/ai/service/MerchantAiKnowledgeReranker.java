package org.example.goshop.merchant.ai.service;

import org.example.goshop.merchant.ai.entity.MerchantAiKnowledgeChunkRow;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用本地词法信号对向量候选进行第二阶段重排。
 *
 * <p>Embedding 擅长召回语义相近的内容，但商品型号、颜色、重量等短词
 * 在长分片中的影响可能被稀释。本重排器只处理 Qdrant 已完成租户过滤的
 * 少量候选，不发起新的远程模型调用；它给商品型号精确命中、中文双字词
 * 覆盖加分，并对缺少型号以及通用回答规则的分片降权。</p>
 */
@Component
public class MerchantAiKnowledgeReranker {

    private static final Pattern ASCII_TOKEN_PATTERN =
            Pattern.compile("[a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAN_SEQUENCE_PATTERN =
            Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern GENERIC_SECTION_PATTERN = Pattern.compile(
            "重要说明|回答边界|典型导购对话|通用说明"
    );
    private static final Set<String> STOP_BIGRAMS = Set.of(
            "什么", "怎么", "如何", "有没有", "哪些", "可以",
            "适合", "真的", "左右", "店里", "我想", "推荐",
            "一下", "这个", "那个", "是否"
    );
    private static final double SEMANTIC_ONLY_MINIMUM_SCORE = 0.60D;

    /**
     * 按综合相关度排序，同时保留原始 Document 供响应返回向量分数。
     */
    public List<RankedKnowledge> rerank(
            String query,
            Map<String, Document> vectorCandidates,
            Map<String, MerchantAiKnowledgeChunkRow> chunkByVectorId
    ) {
        QuerySignals signals = querySignals(query);
        List<RankedKnowledge> ranked = new ArrayList<>();
        for (Document candidate : vectorCandidates.values()) {
            MerchantAiKnowledgeChunkRow chunk =
                    chunkByVectorId.get(candidate.getId());
            if (chunk == null) {
                continue;
            }
            String normalizedContent = normalize(chunk.getContent());
            Set<String> contentFeatures = features(normalizedContent);
            double lexicalCoverage = coverage(
                    signals.features(),
                    contentFeatures
            );
            boolean containsAllModels = signals.modelTokens().isEmpty()
                    || contentFeatures.containsAll(signals.modelTokens());
            boolean hasModelMatch = !signals.modelTokens().isEmpty()
                    && containsAllModels;
            double score = safeVectorScore(candidate)
                    + lexicalCoverage * 0.24D;

            /* 型号是商品问答中最强的实体信号，优先级高于宽泛语义。 */
            if (!signals.modelTokens().isEmpty()) {
                score += containsAllModels ? 0.24D : -0.16D;
            }
            if (GENERIC_SECTION_PATTERN
                    .matcher(normalizedContent)
                    .find()
                    && lexicalCoverage < 0.50D
                    && !hasModelMatch) {
                score -= 0.12D;
            }
            ranked.add(new RankedKnowledge(
                    candidate,
                    chunk,
                    score,
                    lexicalCoverage,
                    hasModelMatch
            ));
        }

        ranked.sort(
                Comparator.comparingDouble(RankedKnowledge::rerankScore)
                        .reversed()
                        .thenComparing(
                                item -> safeVectorScore(item.candidate()),
                                Comparator.reverseOrder()
                        )
        );
        return List.copyOf(ranked);
    }

    /**
     * 中文关键词和型号完全没有交集时，只有较强的纯语义相似度才能通过。
     * 这层门槛用于拦截“手机壳、退款”等知识库外问题，同时为“指针设备”
     * 这类与“鼠标”表述不同但语义足够接近的查询保留召回机会。
     */
    public boolean isRelevant(
            RankedKnowledge knowledge,
            double requestedSemanticThreshold
    ) {
        return knowledge.hasModelMatch()
                || knowledge.lexicalCoverage() > 0D
                || safeVectorScore(knowledge.candidate())
                >= Math.max(
                        SEMANTIC_ONLY_MINIMUM_SCORE,
                        requestedSemanticThreshold
                );
    }

    private QuerySignals querySignals(String query) {
        String normalized = normalize(query);
        Set<String> queryFeatures = features(normalized);
        Set<String> modelTokens = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            boolean containsDigit = token.chars()
                    .anyMatch(Character::isDigit);
            boolean containsLetter = token.chars()
                    .anyMatch(Character::isLetter);
            /*
             * 300、150 等短纯数字通常是预算，不是型号；字母数字组合以及
             * 长业务编号才按强实体处理。
             */
            if (containsDigit
                    && (containsLetter || token.length() >= 8)) {
                modelTokens.add(token);
            }
        }
        return new QuerySignals(queryFeatures, modelTokens);
    }

    /**
     * 中文不依赖额外分词服务，使用连续汉字双字词；英文、数字和商品型号
     * 则按完整 Token 提取，使 K87、M7、H1 等型号不会被拆散。
     */
    private Set<String> features(String text) {
        Set<String> result = new HashSet<>();
        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(text);
        while (asciiMatcher.find()) {
            String token = asciiMatcher.group().toLowerCase(Locale.ROOT);
            if (token.length() >= 2) {
                result.add(token);
            }
        }

        Matcher hanMatcher = HAN_SEQUENCE_PATTERN.matcher(text);
        while (hanMatcher.find()) {
            String sequence = hanMatcher.group();
            for (int index = 0; index + 1 < sequence.length(); index++) {
                String bigram = sequence.substring(index, index + 2);
                if (!STOP_BIGRAMS.contains(bigram)) {
                    result.add(bigram);
                }
            }
        }
        return result;
    }

    private double coverage(Set<String> query, Set<String> content) {
        if (query.isEmpty()) {
            return 0D;
        }
        long matches = query.stream().filter(content::contains).count();
        return (double) matches / query.size();
    }

    private double safeVectorScore(Document document) {
        return document.getScore() == null ? 0D : document.getScore();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    /** 一条携带原始向量候选和 MySQL 正文的重排结果。 */
    public record RankedKnowledge(
            Document candidate,
            MerchantAiKnowledgeChunkRow chunk,
            double rerankScore,
            double lexicalCoverage,
            boolean hasModelMatch
    ) {
    }

    private record QuerySignals(
            Set<String> features,
            Set<String> modelTokens
    ) {
    }
}
