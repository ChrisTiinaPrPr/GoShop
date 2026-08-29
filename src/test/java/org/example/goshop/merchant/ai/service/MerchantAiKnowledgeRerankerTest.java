package org.example.goshop.merchant.ai.service;

import org.example.goshop.merchant.ai.entity.MerchantAiKnowledgeChunkRow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品型号、中文关键词与通用规则降权测试。 */
class MerchantAiKnowledgeRerankerTest {

    private final MerchantAiKnowledgeReranker reranker =
            new MerchantAiKnowledgeReranker();

    @Test
    void shouldRankExactProductEvidenceBeforeHigherVectorBoilerplate() {
        Map<String, Document> candidates = new LinkedHashMap<>();
        candidates.put(
                "generic",
                candidate("generic", 0.68D)
        );
        candidates.put(
                "m7",
                candidate("m7", 0.53D)
        );
        Map<String, MerchantAiKnowledgeChunkRow> chunks = Map.of(
                "generic",
                chunk(
                        "generic",
                        "智能导购回答边界：价格库存以实时接口为准。"
                ),
                "m7",
                chunk(
                        "m7",
                        "星环 M7 无线游戏鼠标，重量 65 克，"
                                + "提供黑色和白色。"
                )
        );

        List<MerchantAiKnowledgeReranker.RankedKnowledge> result =
                reranker.rerank(
                        "M7鼠标多重，有哪些颜色",
                        candidates,
                        chunks
                );

        assertEquals("m7", result.get(0).candidate().getId());
        assertTrue(result.get(0).rerankScore()
                > result.get(1).rerankScore());
    }

    @Test
    void shouldDemoteGenericRulesForProductSelectionQuestion() {
        Map<String, Document> candidates = new LinkedHashMap<>();
        candidates.put("rule", candidate("rule", 0.65D));
        candidates.put("keyboard", candidate("keyboard", 0.61D));
        Map<String, MerchantAiKnowledgeChunkRow> chunks = Map.of(
                "rule",
                chunk("rule", "重要说明：回答必须遵守智能导购边界。"),
                "keyboard",
                chunk(
                        "keyboard",
                        "K87 三模机械键盘支持蓝牙连接，"
                                + "可以连接电脑和平板。"
                )
        );

        List<MerchantAiKnowledgeReranker.RankedKnowledge> result =
                reranker.rerank(
                        "能连接平板的键盘",
                        candidates,
                        chunks
                );

        assertEquals("keyboard", result.get(0).candidate().getId());
    }

    @Test
    void shouldRejectWeakSemanticCandidateWithoutAnyKeywordOverlap() {
        Map<String, Document> candidates = Map.of(
                "cover",
                candidate("cover", 0.56D)
        );
        Map<String, MerchantAiKnowledgeChunkRow> chunks = Map.of(
                "cover",
                chunk("cover", "本店提供电脑外设智能导购服务。")
        );

        MerchantAiKnowledgeReranker.RankedKnowledge knowledge =
                reranker.rerank(
                        "我想买手机壳",
                        candidates,
                        chunks
                ).get(0);

        assertFalse(reranker.isRelevant(knowledge, 0.50D));
    }

    @Test
    void shouldHonorHigherSemanticThresholdWithoutLexicalEvidence() {
        MerchantAiKnowledgeReranker.RankedKnowledge knowledge =
                reranker.rerank(
                        "指针设备",
                        Map.of("mouse", candidate("mouse", 0.72D)),
                        Map.of(
                                "mouse",
                                chunk("mouse", "M7 游戏鼠标")
                        )
                ).get(0);

        assertTrue(reranker.isRelevant(knowledge, 0.70D));
        assertFalse(reranker.isRelevant(knowledge, 0.80D));
    }

    @Test
    void shouldTreatShortNumberAsBudgetInsteadOfProductModel() {
        MerchantAiKnowledgeReranker.RankedKnowledge knowledge =
                reranker.rerank(
                        "预算300元的键盘",
                        Map.of("keyboard", candidate("keyboard", 0.58D)),
                        Map.of(
                                "keyboard",
                                chunk("keyboard", "300元左右的三模键盘")
                        )
                ).get(0);

        assertFalse(knowledge.hasModelMatch());
        assertTrue(knowledge.lexicalCoverage() > 0D);
    }

    private Document candidate(String id, double score) {
        return Document.builder()
                .id(id)
                .text("Qdrant payload")
                .metadata(Map.of())
                .score(score)
                .build();
    }

    private MerchantAiKnowledgeChunkRow chunk(
            String vectorId,
            String content
    ) {
        MerchantAiKnowledgeChunkRow chunk =
                new MerchantAiKnowledgeChunkRow();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId(9001L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        chunk.setOriginalFilename("guide.md");
        return chunk;
    }
}
