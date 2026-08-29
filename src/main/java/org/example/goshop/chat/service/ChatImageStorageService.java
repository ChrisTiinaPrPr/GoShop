package org.example.goshop.chat.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.chat.config.ChatOssProperties;
import org.example.goshop.chat.config.ChatProperties;
import org.example.goshop.chat.dto.ChatImageResponse;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天图片私有 OSS 存储服务。
 *
 * <p>它只负责：</p>
 * <ul>
 *     <li>校验图片大小、扩展名、Content-Type 和真实文件头；</li>
 *     <li>上传到聊天专用私有 Bucket；</li>
 *     <li>生成短时效签名 URL；</li>
 *     <li>删除事务失败后遗留的对象。</li>
 * </ul>
 *
 * <p>数据库中只能保存 objectKey，不能保存签名 URL，
 * 因为签名 URL 会过期。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatImageStorageService {

    private static final String JPEG_MIME = "image/jpeg";
    private static final String PNG_MIME = "image/png";
    private static final String WEBP_MIME = "image/webp";

    private final OSS ossClient;
    private final ChatProperties chatProperties;
    private final ChatOssProperties chatOssProperties;
    private final ObjectMapper objectMapper;

    /**
     * 校验并上传一张聊天图片。
     */
    public UploadResult upload(
            Long conversationId,
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请选择聊天图片");
        }

        /*
         * 先根据 MultipartFile 报告的大小拦截，
         * 后面读取完字节后还会再次检查真实大小。
         */
        if (file.getSize() > chatProperties.imageMaxSizeBytes()) {
            throw new BusinessException(41301, "聊天图片大小超过限制");
        }

        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(50000, "读取聊天图片失败");
        }

        if (bytes.length == 0) {
            throw new BusinessException(40001, "聊天图片不能为空文件");
        }

        if (bytes.length > chatProperties.imageMaxSizeBytes()) {
            throw new BusinessException(41301, "聊天图片大小超过限制");
        }

        /*
         * 不信任前端提交的 Content-Type 和文件扩展名。
         * 先根据真实文件头识别格式，然后进行三方比对。
         */
        String detectedMimeType = detectMimeType(bytes);

        validateDeclaredContentType(
                file.getContentType(),
                detectedMimeType
        );

        validateFilenameExtension(
                file.getOriginalFilename(),
                detectedMimeType
        );

        /*
         * JPEG 和 PNG 使用 JDK ImageIO 读取宽高，同时验证图片结构。
         * JDK 默认不支持 WebP，因此 WebP 宽高暂时允许为 null。
         */
        Dimensions dimensions = readDimensions(
                bytes,
                detectedMimeType
        );

        ChatImageMetadata metadata = new ChatImageMetadata(
                detectedMimeType,
                (long) bytes.length,
                dimensions.width(),
                dimensions.height()
        );

        String metadataJson;

        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50000, "生成聊天图片元数据失败");
        }

        /*
         * 不使用用户原文件名，避免路径注入、中文乱码和文件覆盖。
         * 开发文档规定聊天对象键使用 chat/{conversationId}/{uuid}。
         */

        String objectKey = "chat/%d/%s".formatted(
                conversationId,
                UUID.randomUUID()
        );
        ObjectMetadata ossMetadata = new ObjectMetadata();
        ossMetadata.setContentLength(bytes.length);
        ossMetadata.setContentType(detectedMimeType);

        /*
         * 图片来自私有 Bucket，浏览器只能使用短期签名 URL 读取。
         */
        ossMetadata.setCacheControl("private, max-age=300");
        ossMetadata.setContentDisposition("inline");

        try (ByteArrayInputStream inputStream =
                     new ByteArrayInputStream(bytes)) {

            ossClient.putObject(
                    chatOssProperties.bucketName(),
                    objectKey,
                    inputStream,
                    ossMetadata
            );
        } catch (IOException exception) {
            throw new BusinessException(50000, "关闭聊天图片数据流失败");
        } catch (Exception exception) {
            throw new BusinessException(
                    50000,
                    "上传聊天图片失败，请稍后重试"
            );
        }
        return new UploadResult(objectKey, metadataJson);
    }

    /**
     * 根据数据库保存的 objectKey 和元数据生成图片响应。
     *
     * <p>每次查询历史消息都会重新生成签名 URL，
     * 因此旧 URL 过期后，重新查询即可获得新 URL。</p>
     */
    public ChatImageResponse buildResponse(
            Long conversationId,
            String objectKey,
            String metadataJson
    ) {
        if (!StringUtils.hasText(objectKey) || !StringUtils.hasText(metadataJson)) {
            throw new BusinessException(50000, "聊天图片数据不完整");
        }

        /*
         * 防止数据库异常数据引用其他会话的图片对象。
         */
        String expectedPrefix = "chat/" + conversationId + "/";

        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BusinessException(
                    50000,
                    "聊天图片与会话归属不一致"
            );
        }

        ChatImageMetadata metadata;
        try {
            metadata = objectMapper.readValue(
                    metadataJson,
                    ChatImageMetadata.class
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50000, "聊天图片元数据格式异常");
        }

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(chatOssProperties.signedUrlMinutes()));

        URL signedUrl;

        try {
            signedUrl = ossClient.generatePresignedUrl(
                    chatOssProperties.bucketName(),
                    objectKey,
                    Date.from(expiresAt)
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    50000,
                    "生成聊天图片访问地址失败"
            );
        }
        return new ChatImageResponse(
                signedUrl.toString(),
                metadata.mimeType(),
                metadata.sizeBytes(),
                metadata.width(),
                metadata.height(),
                expiresAt
        );
    }

    /**
     * 删除未成功关联到聊天消息的 OSS 对象。
     *
     * <p>清理失败不覆盖原业务异常，后续可以通过孤儿对象定时任务再次清理。</p>
     */
    public void deleteQuietly(String objectKey) {

        if (!StringUtils.hasText(objectKey)) {
            return;
        }

        try {
            ossClient.deleteObject(
                    chatOssProperties.bucketName(),
                    objectKey
            );
        } catch (Exception ignored) {
            // 这里不能抛异常，否则可能覆盖真正的数据库错误。
        }
    }

    /**
     * 根据文件头识别真实图片类型。
     */
    private String detectMimeType(byte[] bytes) {
        if (isJpeg(bytes)) {
            return JPEG_MIME;
        }

        if (isPng(bytes)) {
            return PNG_MIME;
        }

        if (isWebp(bytes)) {
            return WEBP_MIME;
        }

        throw new BusinessException(
                41301,
                "聊天图片只支持 JPEG、PNG 或 WebP"
        );
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && unsigned(bytes[0]) == 0xFF
                && unsigned(bytes[1]) == 0xD8
                && unsigned(bytes[2]) == 0xFF;
    }

    private boolean isPng(byte[] bytes) {
        byte[] pngSignature = new byte[]{
                (byte) 0x89,
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A
        };

        return bytes.length >= pngSignature.length
                && Arrays.equals(
                Arrays.copyOf(bytes, pngSignature.length),
                pngSignature
        );
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    /**
     * 校验浏览器声明的 Content-Type 与真实文件类型一致。
     */
    private void validateDeclaredContentType(String declaredContentType,String detectedMimeType) {

        if (!StringUtils.hasText(declaredContentType)) {
            throw new BusinessException(41301, "聊天图片缺少 Content-Type");
        }

        String normalized = declaredContentType.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(detectedMimeType)) {
            throw new BusinessException(
                    41301,
                    "聊天图片 Content-Type 与真实格式不一致"
            );
        }
    }

    /**
     * 校验扩展名，防止把可执行文件简单改名后上传。
     */
    private void validateFilenameExtension(String originalFilename,String detectedMimeType) {

        String extension = StringUtils.getFilenameExtension(originalFilename);

        if (!StringUtils.hasText(extension)) {
            throw new BusinessException(41301, "聊天图片缺少扩展名");
        }

        String normalized = extension.toLowerCase(Locale.ROOT);

        boolean matched = switch (detectedMimeType) {
            case JPEG_MIME ->
                    normalized.equals("jpg")
                            || normalized.equals("jpeg");
            case PNG_MIME -> normalized.equals("png");
            case WEBP_MIME -> normalized.equals("webp");
            default -> false;
        };

        if (!matched) {
            throw new BusinessException(
                    41301,
                    "聊天图片扩展名与真实格式不一致"
            );
        }
    }

    /**
     * 读取 JPEG 或 PNG 宽高。
     *
     * <p>这里只读取图片头，不把整张图片解码成 BufferedImage，
     * 可以减少超大分辨率图片造成的内存压力。</p>
     */
    private Dimensions readDimensions(byte[] bytes,String mimeType) {

        if (WEBP_MIME.equals(mimeType)) {
            return new Dimensions(null, null);
        }

        try (ImageInputStream imageInput =
                     ImageIO.createImageInputStream(
                             new ByteArrayInputStream(bytes)
                     )) {

            if (imageInput == null) {
                throw new BusinessException(
                        41301,
                        "无法识别聊天图片"
                );
            }

            Iterator<ImageReader> readers =
                    ImageIO.getImageReaders(imageInput);

            if (!readers.hasNext()) {
                throw new BusinessException(
                        41301,
                        "聊天图片内容已损坏"
                );
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(imageInput, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0 || height <= 0) {
                    throw new BusinessException(
                            41301,
                            "聊天图片尺寸无效"
                    );
                }

                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(
                    41301,
                    "聊天图片内容已损坏"
            );
        }
    }

    /**
     * 写入 chat_message.image_meta_json 的数据。
     */
    public record ChatImageMetadata(
            String mimeType,
            Long sizeBytes,
            Integer width,
            Integer height
    ) {
    }

    /**
     * 上传成功后交给 ChatService 保存的结果。
     */
    public record UploadResult(
            String objectKey,
            String metadataJson
    ) {
    }

    private record Dimensions(
            Integer width,
            Integer height
    ) {
    }
}
