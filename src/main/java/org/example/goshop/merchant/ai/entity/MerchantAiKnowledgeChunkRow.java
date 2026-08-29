package org.example.goshop.merchant.ai.entity;

import lombok.Data;

/**
 * 知识检索结果的 MySQL 复核投影。
 *
 * <p>Qdrant 是可重建的向量索引，不能作为文档是否仍然有效的最终依据。
 * 检索命中后通过本投影联表校验商家、助手和 READY 状态，并从 MySQL
 * 读取正文，避免返回已经删除的孤儿向量或被篡改的向量载荷。</p>
 */
@Data
public class MerchantAiKnowledgeChunkRow {

    private String vectorId;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private String originalFilename;
}
