package org.example.goshop.merchant.ai.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiDocumentProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导购文档私有 OSS 存储与文件真实性校验服务。
 *
 * <p>上传文件属于商家私有知识资产，必须存入私有 Bucket。服务端不会
 * 信任浏览器提交的文件名或 Content-Type，而会同时比对扩展名、MIME
 * 与文件特征。数据库和 API 均不暴露对象键。</p>
 */
@Service
@RequiredArgsConstructor
public class MerchantAiDocumentStorageService {

    private static final String OCTET_STREAM =
            "application/octet-stream";
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String TEXT_MIME = "text/plain";
    private static final String MARKDOWN_MIME = "text/markdown";

    private static final Map<String, FileType> EXTENSION_TYPES =
            Map.of(
                    "pdf", FileType.PDF,
                    "docx", FileType.DOCX,
                    "txt", FileType.TXT,
                    "md", FileType.MARKDOWN,
                    "markdown", FileType.MARKDOWN
            );

    private final OSS ossClient;
    private final MerchantAiDocumentProperties properties;

    /**
     * 将 Multipart 文件读取到受大小限制的内存并完成全部格式校验。
     *
     * <p>最大文件只有 5 MB；一次读取便于后续计算摘要并保证校验内容
     * 与实际上传内容完全相同。</p>
     */
    public PreparedDocument prepare(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请选择导购文档");
        }
        if (file.getSize() > properties.maxSizeBytes()) {
            throw new BusinessException(41301, "导购文档大小超过限制");
        }

        String originalFilename = sanitizeFilename(
                file.getOriginalFilename()
        );
        String extension = extensionOf(originalFilename);
        FileType fileType = EXTENSION_TYPES.get(extension);
        if (fileType == null) {
            throw new BusinessException(
                    41301,
                    "导购文档只支持 PDF、DOCX、TXT 或 Markdown"
            );
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(50000, "读取导购文档失败");
        }
        if (bytes.length == 0) {
            throw new BusinessException(40001, "导购文档不能为空文件");
        }
        if (bytes.length > properties.maxSizeBytes()) {
            throw new BusinessException(41301, "导购文档大小超过限制");
        }

        validateFileContent(fileType, bytes);
        validateDeclaredContentType(
                fileType,
                file.getContentType()
        );

        return new PreparedDocument(
                originalFilename,
                fileType.name(),
                fileType.canonicalMimeType,
                extension,
                (long) bytes.length,
                sha256(bytes),
                bytes
        );
    }

    /** 上传已经完成真实性校验的文档。 */
    public UploadResult upload(
            Long merchantId,
            Long documentId,
            PreparedDocument document
    ) {
        String objectKey =
                "merchant-ai/documents/%d/%s/%d-%s.%s"
                        .formatted(
                                merchantId,
                                LocalDate.now()
                                        .toString()
                                        .replace("-", "/"),
                                documentId,
                                UUID.randomUUID(),
                                document.extension()
                        );

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(document.sizeBytes());
        metadata.setContentType(document.mimeType());
        metadata.setCacheControl("private, no-store");
        /* 不把用户文件名放入 HTTP Header，避免 CRLF 和编码问题。 */
        metadata.setContentDisposition("attachment");

        try (ByteArrayInputStream inputStream =
                     new ByteArrayInputStream(document.bytes())) {
            ossClient.putObject(
                    properties.bucketName(),
                    objectKey,
                    inputStream,
                    metadata
            );
        } catch (IOException exception) {
            throw new BusinessException(50000, "关闭导购文档数据流失败");
        } catch (Exception exception) {
            throw new BusinessException(
                    50000,
                    "上传导购文档失败，请稍后重试"
            );
        }

        return new UploadResult(objectKey);
    }

    /**
     * 从私有 OSS 下载当前商家拥有的原始导购文档。
     *
     * <p>对象键来自数据库，但仍校验租户目录前缀，避免错误数据使处理任务
     * 越权读取其他商家的文件。读取时再次应用上传大小上限，防止 OSS 对象
     * 被外部方式替换后导致工作线程内存异常。</p>
     */
    public byte[] download(Long merchantId, String objectKey) {
        String ownedPrefix =
                "merchant-ai/documents/%d/".formatted(merchantId);
        if (merchantId == null
                || !StringUtils.hasText(objectKey)
                || !objectKey.startsWith(ownedPrefix)) {
            throw new BusinessException(
                    50000,
                    "导购文档存储信息不合法"
            );
        }

        try (OSSObject object = ossClient.getObject(
                properties.bucketName(),
                objectKey
        ); var inputStream = object.getObjectContent()) {
            byte[] bytes = inputStream.readNBytes(
                    Math.toIntExact(properties.maxSizeBytes() + 1)
            );
            if (bytes.length == 0) {
                throw new BusinessException(
                        42201,
                        "导购文档内容为空"
                );
            }
            if (bytes.length > properties.maxSizeBytes()) {
                throw new BusinessException(
                        41301,
                        "导购文档大小超过限制"
                );
            }
            return bytes;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    50000,
                    "读取导购文档失败，请稍后重试"
            );
        }
    }

    /** 删除数据库事务失败后未被引用的私有 OSS 对象。 */
    public void deleteQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            ossClient.deleteObject(
                    properties.bucketName(),
                    objectKey
            );
        } catch (Exception ignored) {
            // 清理失败不能覆盖真正业务异常，后续由孤儿对象任务兜底。
        }
    }

    private String sanitizeFilename(String originalFilename) {
        String cleaned = StringUtils.cleanPath(
                originalFilename == null ? "" : originalFilename.trim()
        );
        String filename = StringUtils.getFilename(cleaned);
        if (!StringUtils.hasText(filename)) {
            throw new BusinessException(40001, "导购文档缺少文件名");
        }
        if (filename.length() > 255) {
            throw new BusinessException(40001, "导购文档文件名不能超过255个字符");
        }
        return filename;
    }

    private String extensionOf(String filename) {
        String extension = StringUtils.getFilenameExtension(filename);
        return extension == null
                ? ""
                : extension.toLowerCase(Locale.ROOT);
    }

    private void validateFileContent(
            FileType fileType,
            byte[] bytes
    ) {
        boolean valid = switch (fileType) {
            case PDF -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case DOCX -> isDocx(bytes);
            case TXT, MARKDOWN -> isUtf8Text(bytes);
        };
        if (!valid) {
            throw new BusinessException(
                    41301,
                    "导购文档扩展名与真实格式不一致或文件已损坏"
            );
        }
    }

    private boolean isDocx(byte[] bytes) {
        byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04};
        if (!startsWith(bytes, zipHeader)) {
            return false;
        }

        /*
         * DOCX 是 ZIP 容器，条目名以明文保存在本地头和中央目录中。
         * 同时出现内容类型清单与 Word 主文档，可排除普通 ZIP 改名。
         */
        return containsAscii(bytes, "[Content_Types].xml")
                && containsAscii(bytes, "word/document.xml");
    }

    private boolean isUtf8Text(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private void validateDeclaredContentType(
            FileType fileType,
            String declaredContentType
    ) {
        if (!StringUtils.hasText(declaredContentType)) {
            throw new BusinessException(41301, "导购文档缺少 Content-Type");
        }
        String normalized = declaredContentType
                .trim()
                .toLowerCase(Locale.ROOT);
        /* Multipart MIME 可能携带 charset 等参数，格式判断只比较主类型。 */
        int parameterStart = normalized.indexOf(';');
        if (parameterStart >= 0) {
            normalized = normalized
                    .substring(0, parameterStart)
                    .trim();
        }

        Set<String> allowed = switch (fileType) {
            case PDF -> Set.of(PDF_MIME, OCTET_STREAM);
            case DOCX -> Set.of(
                    DOCX_MIME,
                    "application/zip",
                    OCTET_STREAM
            );
            case TXT -> Set.of(TEXT_MIME, OCTET_STREAM);
            case MARKDOWN -> Set.of(
                    MARKDOWN_MIME,
                    TEXT_MIME,
                    OCTET_STREAM
            );
        };
        if (!allowed.contains(normalized)) {
            throw new BusinessException(
                    41301,
                    "导购文档 Content-Type 与文件格式不一致"
            );
        }
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (source[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAscii(byte[] source, String expected) {
        byte[] target = expected.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int index = 0;
             index <= source.length - target.length;
             index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前 Java 运行时不支持 SHA-256",
                    exception
            );
        }
    }

    private enum FileType {
        PDF(PDF_MIME),
        DOCX(DOCX_MIME),
        TXT(TEXT_MIME),
        MARKDOWN(MARKDOWN_MIME);

        private final String canonicalMimeType;

        FileType(String canonicalMimeType) {
            this.canonicalMimeType = canonicalMimeType;
        }
    }

    public record PreparedDocument(
            String originalFilename,
            String fileType,
            String mimeType,
            String extension,
            Long sizeBytes,
            String sha256,
            byte[] bytes
    ) {
    }

    public record UploadResult(String objectKey) {
    }
}
