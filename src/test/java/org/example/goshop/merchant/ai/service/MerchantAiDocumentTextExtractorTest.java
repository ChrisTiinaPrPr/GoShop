package org.example.goshop.merchant.ai.service;

import org.example.goshop.merchant.ai.config.MerchantAiRagProperties;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档正文提取、中文分片与向量元数据测试。 */
class MerchantAiDocumentTextExtractorTest {

    private final MerchantAiDocumentTextExtractor extractor =
            new MerchantAiDocumentTextExtractor(
                    new MerchantAiRagProperties(
                            true,
                            100,
                            20,
                            5,
                            50,
                            20_000,
                            Duration.ofMinutes(15),
                            1,
                            2,
                            10
                    )
            );

    @Test
    void shouldExtractUtf8TextWithTenantMetadata() {
        MerchantAiDocument document = new MerchantAiDocument();
        document.setId(9001L);
        document.setAssistantId(8001L);
        document.setMerchantId(7001L);
        document.setOriginalFilename("键盘选购指南.txt");
        document.setFileType("TXT");

        String text = "机械键盘适合重视手感的用户。\n\n"
                + "静音办公建议选择线性轴，并搭配消音棉。\n"
                + "游戏用户可以关注低延迟连接和全键无冲功能。";
        List<MerchantAiDocumentTextExtractor.PreparedChunk> chunks =
                extractor.extract(
                        document,
                        text.getBytes(StandardCharsets.UTF_8)
                );

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).content().contains("机械键盘"));
        assertEquals(
                "7001",
                chunks.get(0).vectorDocument()
                        .getMetadata()
                        .get("merchant_id")
        );
        assertEquals(36, chunks.get(0).vectorId().length());
    }

    @Test
    void shouldKeepMarkdownProductSectionsIndependent() {
        MerchantAiDocument document = new MerchantAiDocument();
        document.setId(9002L);
        document.setAssistantId(8002L);
        document.setMerchantId(7002L);
        document.setOriginalFilename("外设导购.md");
        document.setFileType("MARKDOWN");

        String markdown = """
                # 星环外设馆
                > 文档版本：2026-08-11
                > 适用店铺：星环外设馆
                > 用途：智能导购检索测试
                ## 商品介绍
                ### K87 三模机械键盘
                K87 支持蓝牙和有线连接，红轴适合连续输入。
                ### M7 无线游戏鼠标
                M7 重量为 65 克，提供黑色和白色。
                ### H1 无线游戏耳机
                H1 支持低延迟连接和可拆卸麦克风。
                ## 六、建议测试问题
                1. M7 鼠标有哪些颜色？
                """;

        List<MerchantAiDocumentTextExtractor.PreparedChunk> chunks =
                extractor.extract(
                        document,
                        markdown.getBytes(StandardCharsets.UTF_8)
                );

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.content().contains("K87")
                        && !chunk.content().contains("M7")));
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.content().contains("M7")
                        && !chunk.content().contains("H1")));
        assertTrue(chunks.stream().allMatch(chunk ->
                chunk.vectorDocument().getMetadata()
                        .containsKey("section_path")));
        assertTrue(chunks.stream().noneMatch(chunk ->
                chunk.content().contains("文档版本")
                        || chunk.content().contains("建议测试问题")));
    }
}
