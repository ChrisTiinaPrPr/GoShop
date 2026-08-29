package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import org.apache.tika.sax.BodyContentHandler;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiRagProperties;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将私有 OSS 原文件安全提取并转换为可入库的 RAG 分片。 */
@Service
@RequiredArgsConstructor
public class MerchantAiDocumentTextExtractor {

    private static final List<Character> PUNCTUATION_MARKS = List.of(
            '.', ',', '!', '?', ';', ':',
            '。', '，', '！', '？', '；', '：', '\n'
    );
    private static final Pattern MARKDOWN_HEADING = Pattern.compile(
            "^(#{1,6})\\s+(.+?)\\s*$"
    );
    private static final Pattern NON_KNOWLEDGE_SECTION = Pattern.compile(
            "(^| > )(?:[一二三四五六七八九十0-9]+[、.．]\\s*)?"
                    + "(建议)?(测试|验收)(问题|用例)$"
    );
    private static final int SECTION_OVERLAP_CHARS = 120;

    private final MerchantAiRagProperties properties;

    /**
     * 使用 Tika 解析受支持文档，并按 Token 而非 Java 字符数分片。
     *
     * <p>BodyContentHandler 在提取阶段限制字符总量，用于抑制压缩型 DOCX
     * 的异常膨胀；分片器优先在中英文标点附近断句，降低语义被截断的概率。</p>
     */
    public List<PreparedChunk> extract(
            MerchantAiDocument source,
            byte[] bytes
    ) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return source.getOriginalFilename();
            }
        };

        List<Document> extracted;
        try {
            extracted = new TikaDocumentReader(
                    resource,
                    new BodyContentHandler(
                            properties.maxExtractedChars()
                    ),
                    ExtractedTextFormatter.defaults()
            ).get();
        } catch (Exception exception) {
            throw new BusinessException(
                    42201,
                    "无法提取导购文档正文，请检查文件是否损坏"
            );
        }

        TokenTextSplitter splitter = new TokenTextSplitter(
                properties.chunkSize(),
                properties.minChunkChars(),
                properties.minEmbedChars(),
                properties.maxChunks(),
                true,
                PUNCTUATION_MARKS
        );
        List<Document> splitDocuments = new ArrayList<>();
        for (Document section : semanticSections(extracted)) {
            splitDocuments.addAll(splitSection(splitter, section));
        }
        List<PreparedChunk> result = new ArrayList<>();
        for (Document split : splitDocuments) {
            String content = normalize(split.getText());
            if (!StringUtils.hasText(content)) {
                continue;
            }
            if (result.size() >= properties.maxChunks()) {
                throw new BusinessException(
                        42201,
                        "导购文档内容过长，产生的分片数量超过限制"
                );
            }

            int chunkIndex = result.size();
            String vectorId = deterministicVectorId(
                    source.getId(),
                    chunkIndex
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            /* 所有检索条件都携带租户与助手字段，避免跨店召回。 */
            metadata.put("merchant_id", source.getMerchantId().toString());
            metadata.put("assistant_id", source.getAssistantId().toString());
            metadata.put("document_id", source.getId().toString());
            metadata.put("chunk_index", chunkIndex);
            metadata.put("source_filename", source.getOriginalFilename());
            Object sectionPath = split.getMetadata().get("section_path");
            if (sectionPath instanceof String path
                    && StringUtils.hasText(path)) {
                metadata.put("section_path", path);
            }

            result.add(new PreparedChunk(
                    chunkIndex,
                    vectorId,
                    content,
                    sha256(content),
                    new Document(vectorId, content, metadata)
            ));
        }
        if (result.isEmpty()) {
            throw new BusinessException(
                    42201,
                    "导购文档未提取到可用于导购的正文"
            );
        }
        return List.copyOf(result);
    }

    /**
     * 优先按 Markdown 标题边界建立语义章节，防止一个向量同时包含键盘、
     * 鼠标和耳机等多个商品。没有 Markdown 标题的 PDF、DOCX、TXT 会自然
     * 退化为单章节，继续由 TokenTextSplitter 按标点安全切分。
     */
    private List<Document> semanticSections(List<Document> extracted) {
        List<Document> sections = new ArrayList<>();
        for (Document document : extracted) {
            String text = normalize(document.getText());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            sections.addAll(markdownSections(text));
        }
        return sections;
    }

    private List<Document> markdownSections(String text) {
        List<Document> sections = new ArrayList<>();
        String[] headingStack = new String[6];
        String currentPath = "";
        StringBuilder body = new StringBuilder();
        boolean foundHeading = false;

        for (String line : text.split("\\n", -1)) {
            Matcher heading = MARKDOWN_HEADING.matcher(line.strip());
            if (!heading.matches()) {
                body.append(line).append('\n');
                continue;
            }

            foundHeading = true;
            addSectionIfPresent(sections, currentPath, body);
            body.setLength(0);

            int level = heading.group(1).length();
            headingStack[level - 1] = heading.group(2).strip();
            for (int index = level; index < headingStack.length; index++) {
                headingStack[index] = null;
            }
            currentPath = headingPath(headingStack);
        }
        addSectionIfPresent(sections, currentPath, body);

        if (!foundHeading) {
            return List.of(document(text, ""));
        }
        return List.copyOf(sections);
    }

    /** 标题本身没有正文时不产生无意义的极短向量。 */
    private void addSectionIfPresent(
            List<Document> sections,
            String sectionPath,
            StringBuilder body
    ) {
        String normalizedBody = normalize(body.toString());
        if (!StringUtils.hasText(normalizedBody)) {
            return;
        }
        if (NON_KNOWLEDGE_SECTION.matcher(sectionPath).find()
                || isDocumentFrontMatter(normalizedBody)) {
            return;
        }
        String content = StringUtils.hasText(sectionPath)
                ? sectionPath + "\n" + normalizedBody
                : normalizedBody;
        sections.add(document(content, sectionPath));
    }

    /**
     * 文档版本、适用店铺、用途等封面元数据不包含商品事实，向量化后容易
     * 因“导购、回答、测试”等宽泛词误召回，因此在入库前直接剔除。
     */
    private boolean isDocumentFrontMatter(String body) {
        if (body.length() > 300) {
            return false;
        }
        int markerCount = 0;
        for (String marker : List.of("文档版本", "适用店铺", "用途")) {
            if (body.contains(marker)) {
                markerCount++;
            }
        }
        return markerCount >= 2;
    }

    private String headingPath(String[] headingStack) {
        List<String> headings = new ArrayList<>();
        for (String heading : headingStack) {
            if (StringUtils.hasText(heading)) {
                headings.add(heading);
            }
        }
        return String.join(" > ", headings);
    }

    private Document document(String content, String sectionPath) {
        Map<String, Object> metadata = StringUtils.hasText(sectionPath)
                ? Map.of("section_path", sectionPath)
                : Map.of();
        return new Document(content, metadata);
    }

    /**
     * 对超长章节继续按 Token 切分，并给后续分片补回章节标题和少量上文。
     * 这既保留商品身份，也降低关键参数刚好落在分片边界时的漏召回概率。
     */
    private List<Document> splitSection(
            TokenTextSplitter splitter,
            Document section
    ) {
        List<Document> rawChunks = splitter.apply(List.of(section));
        if (rawChunks.size() <= 1) {
            return rawChunks;
        }

        String sectionPath = String.valueOf(
                section.getMetadata().getOrDefault("section_path", "")
        );
        List<Document> contextualChunks = new ArrayList<>();
        String previousRawText = "";
        for (int index = 0; index < rawChunks.size(); index++) {
            Document raw = rawChunks.get(index);
            String rawText = normalize(raw.getText());
            StringBuilder content = new StringBuilder();
            if (StringUtils.hasText(sectionPath)
                    && !rawText.startsWith(sectionPath)) {
                content.append(sectionPath).append('\n');
            }
            if (index > 0) {
                String overlap = trailingContext(previousRawText);
                if (StringUtils.hasText(overlap)) {
                    content.append(overlap).append('\n');
                }
            }
            content.append(rawText);
            contextualChunks.add(document(
                    normalize(content.toString()),
                    sectionPath
            ));
            previousRawText = rawText;
        }
        return List.copyOf(contextualChunks);
    }

    private String trailingContext(String text) {
        if (!StringUtils.hasText(text)
                || text.length() <= SECTION_OVERLAP_CHARS) {
            return text;
        }
        int start = text.length() - SECTION_OVERLAP_CHARS;
        for (int index = start; index < text.length(); index++) {
            if (PUNCTUATION_MARKS.contains(text.charAt(index))) {
                return text.substring(index + 1).strip();
            }
        }
        return text.substring(start).strip();
    }

    /** 统一换行并压缩无意义空白，同时保留段落边界用于回答引用。 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** 相同文档的相同位置始终得到同一个 UUID，使 Qdrant 重试具有幂等性。 */
    private String deterministicVectorId(
            Long documentId,
            int chunkIndex
    ) {
        return UUID.nameUUIDFromBytes(
                (documentId + ":" + chunkIndex)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前 Java 运行时不支持 SHA-256",
                    exception
            );
        }
    }

    /** 一份同时供 MySQL 与 Qdrant 使用的不可变分片。 */
    public record PreparedChunk(
            int chunkIndex,
            String vectorId,
            String content,
            String contentHash,
            Document vectorDocument
    ) {
    }
}
