package org.example.goshop.merchant.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导购文档分片事实记录。
 *
 * <p>MySQL 保存可审计、可重建的分片正文，Qdrant 仅保存由这些记录生成的
 * 向量副本。vectorId 使用确定性 UUID，重试处理时会覆盖同一向量点。</p>
 */
@Data
@TableName("merchant_ai_document_chunk")
public class MerchantAiDocumentChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long documentId;
    private Long assistantId;
    private Long merchantId;
    private Integer chunkIndex;
    private String vectorId;
    private String content;
    private String contentHash;
    private LocalDateTime createdAt;
}
