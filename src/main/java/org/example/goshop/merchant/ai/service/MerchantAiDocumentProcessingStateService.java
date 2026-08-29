package org.example.goshop.merchant.ai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.example.goshop.merchant.ai.entity.MerchantAiDocumentChunk;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentChunkMapper;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 文档处理结果的短事务写入服务，避免数据库事务覆盖外部模型调用。 */
@Service
@RequiredArgsConstructor
public class MerchantAiDocumentProcessingStateService {

    private final MerchantAiDocumentMapper documentMapper;
    private final MerchantAiDocumentChunkMapper chunkMapper;

    /** 原子替换分片事实记录，并且仅在全部插入成功后发布 READY 状态。 */
    @Transactional
    public void replaceChunksAndMarkReady(
            Long documentId,
            Long assistantId,
            Long merchantId,
            List<MerchantAiDocumentTextExtractor.PreparedChunk> chunks
    ) {
        chunkMapper.deleteByOwnedDocument(documentId, merchantId);
        LocalDateTime now = now();
        for (MerchantAiDocumentTextExtractor.PreparedChunk prepared : chunks) {
            MerchantAiDocumentChunk chunk =
                    new MerchantAiDocumentChunk();
            chunk.setId(IdWorker.getId());
            chunk.setDocumentId(documentId);
            chunk.setAssistantId(assistantId);
            chunk.setMerchantId(merchantId);
            chunk.setChunkIndex(prepared.chunkIndex());
            chunk.setVectorId(prepared.vectorId());
            chunk.setContent(prepared.content());
            chunk.setContentHash(prepared.contentHash());
            chunk.setCreatedAt(now);
            if (chunkMapper.insert(chunk) != 1) {
                throw new IllegalStateException("保存导购文档分片失败");
            }
        }

        if (documentMapper.markReady(
                documentId,
                merchantId,
                chunks.size(),
                now
        ) != 1) {
            throw new IllegalStateException("发布导购文档处理结果失败");
        }
    }

    /** 失败原因只保留可展示文本，不把异常堆栈、密钥或远端响应写入数据库。 */
    @Transactional
    public void markFailed(
            Long documentId,
            Long merchantId,
            String failureReason
    ) {
        String safeReason = failureReason == null
                ? "导购文档处理失败，请稍后重试"
                : failureReason.strip();
        if (safeReason.length() > 500) {
            safeReason = safeReason.substring(0, 500);
        }
        documentMapper.markFailed(
                documentId,
                merchantId,
                safeReason,
                now()
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
