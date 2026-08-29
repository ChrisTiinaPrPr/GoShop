package org.example.goshop.merchant.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家智能导购文档元数据。
 *
 * <p>原文件保存在私有 OSS；数据库只保存受控对象键和校验元数据。
 * 后续解析、分片和向量化任务通过 status 推进处理状态。</p>
 */
@Data
@TableName("merchant_ai_document")
public class MerchantAiDocument {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long assistantId;
    private Long merchantId;
    private String originalFilename;
    private String objectKey;
    private String fileType;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private String failureReason;
    private Integer chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
