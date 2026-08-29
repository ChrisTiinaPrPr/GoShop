package org.example.goshop.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OssStorageService {

    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp"
    );

    private final OSS ossClient;
    private final OssProperties properties;

    public OssUploadResult uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请选择头像文件");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(40001, "图片大小不能超过5M");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40001, "头像只支持 JPG、PNG、WebP 格式");
        }

        String extension = getExtension(contentType);

        // 不使用用户原始文件名，避免路径注入和中文乱码问题。
        String objectKey = "avatars/%d/%s/%s.%s".formatted(
                userId,
                LocalDate.now().toString().replace("-","/"),
                UUID.randomUUID(),
                extension
        );

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        metadata.setCacheControl("max-age=31536000");

        try {
            ossClient.putObject(
                    properties.bucketName(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );
        } catch (IOException e) {
            throw new BusinessException(50000, "上传头像失败");
        } catch (Exception e) {
            throw new BusinessException(50000, "上传头像失败，请稍后重试");
        }
        return new OssUploadResult(objectKey,buildPublicUrl(objectKey));
    }

    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }

        try {
            ossClient.deleteObject(properties.bucketName(), objectKey);
        } catch (Exception ignored) {
            // 删除旧头像失败不影响资料更新；后续可用定时任务清理孤儿文件。
        }
    }

    private String getExtension(String contentType) {
        return switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> "jpg";
            case MediaType.IMAGE_PNG_VALUE -> "png";
            case "image/webp" -> "webp";
            default -> throw new BusinessException(40001, "不支持的头像格式");
        };
    }

    private String buildPublicUrl(String objectKey) {
        String endpoint = properties.endpoint().replaceAll("/+$", "");

        // 标准 OSS Endpoint：
        // https://oss-cn-hangzhou.aliyuncs.com
        // → https://bucket.oss-cn-hangzhou.aliyuncs.com/avatars/...
        int schemeEnd = endpoint.indexOf("://");
        if (schemeEnd < 0) {
            throw new BusinessException(50000, "OSS Endpoint 配置错误");
        }

        String scheme = endpoint.substring(0, schemeEnd + 3);
        String host = endpoint.substring(schemeEnd + 3);

        return scheme + properties.bucketName() + "." + host + "/" + objectKey;
    }

    public OssUploadResult uploadMerchantLogo(Long userId,MultipartFile  file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请上传店铺Logo");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(40001, "图片大小不能超过5M");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40001, "图片只支持 JPG、PNG、WebP 格式");
        }

        String extension = getExtension(contentType);

        // 使用当前用户 ID 与随机 UUID 生成对象键，不适用原始文件名
        String objectKey = "merchant-logos/%d/%s/%s.%s".formatted(
                userId,
                LocalDate.now().toString().replace("-","/"),
                UUID.randomUUID(),
                extension
        );

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        metadata.setCacheControl("max-age=31536000");
        try {
            ossClient.putObject(
                    properties.bucketName(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );
        } catch (IOException e) {
            throw new BusinessException(50000, "上传店铺Logo失败");
        } catch (Exception e) {
            throw new BusinessException(50000, "上传店铺Logo失败，请稍后重试");
        }
        return new OssUploadResult(objectKey,buildPublicUrl(objectKey));
    }

    public OssUploadResult uploadProductMainImage(Long merchantId,MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请上传商品主图");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(40001, "图片大小不能超过5M");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40001, "图片只支持 JPG、PNG、WebP 格式");
        }

        assert contentType != null;
        String extension = getExtension(contentType);

        // 不使用原始文件名，避免路径注入与重名覆盖。
        String objectKey = "product-images/%d/%s/%s.%s".formatted(
                merchantId,
                LocalDate.now().toString().replace("-","/"),
                UUID.randomUUID(),
                extension
        );
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        metadata.setCacheControl("max-age=31536000");

        try {
            ossClient.putObject(
                    properties.bucketName(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );
        } catch (IOException exception) {
            throw new BusinessException(50000, "上传商品主图失败");
        } catch (Exception exception) {
            throw new BusinessException(50000, "上传商品主图失败，请稍后重试");
        }
        return new OssUploadResult(objectKey,buildPublicUrl(objectKey));
    }

    public OssUploadResult uploadProductDetailImage(
            Long merchantId,
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请上传商品详情图");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException(40001, "图片大小不能超过5MB");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40001, "图片只支持 JPG、PNG、WebP 格式");
        }

        String objectKey = "product-detail-images/%d/%s/%s.%s".formatted(
                merchantId,
                LocalDate.now().toString().replace("-","/"),
                UUID.randomUUID(),
                getExtension(contentType)
        );

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType);
        metadata.setCacheControl("max-age=31536000");

        try {
            ossClient.putObject(
                    properties.bucketName(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );
            return new OssUploadResult(objectKey,buildPublicUrl(objectKey));
        } catch (IOException exception) {
            throw new BusinessException(50000, "上传商品详情图失败");
        } catch (Exception exception) {
            throw new BusinessException(50000, "上传商品详情图失败，请稍后重试");
        }
    }
}
