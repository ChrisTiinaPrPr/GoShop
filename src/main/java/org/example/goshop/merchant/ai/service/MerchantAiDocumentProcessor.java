package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentMapper;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/** 在后台线程中执行文档解析、Embedding、Qdrant 写入和状态收口。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAiDocumentProcessor {

    private final MerchantAiDocumentMapper documentMapper;
    private final MerchantAiDocumentStorageService storageService;
    private final MerchantAiDocumentTextExtractor textExtractor;
    private final MerchantAiDocumentProcessingStateService stateService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * 处理一份已经被请求事务认领为 PROCESSING 的文档。
     *
     * <p>MySQL 事务不会跨越 OSS、Embedding 或 Qdrant 调用。向量点采用
     * 确定性 UUID，且写入前按 document_id 删除旧点，因此失败重试不会产生
     * 重复召回。数据库发布 READY 失败时再次清理向量，尽量保持两端一致。</p>
     */
    public void process(Long documentId, Long merchantId) {
        MerchantAiDocument source =
                documentMapper.selectOwnedDocument(
                        documentId,
                        merchantId
                );
        if (source == null
                || !"PROCESSING".equals(source.getStatus())) {
            return;
        }

        VectorStore vectorStore = null;
        Filter.Expression documentFilter = documentFilter(documentId);
        try {
            byte[] bytes = storageService.download(
                    merchantId,
                    source.getObjectKey()
            );
            List<MerchantAiDocumentTextExtractor.PreparedChunk> chunks =
                    textExtractor.extract(source, bytes);

            vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new BusinessException(
                        50301,
                        "Embedding 或 Qdrant 尚未配置"
                );
            }

            /* 先清旧点再幂等写入，覆盖 FAILED 文档的重试场景。 */
            vectorStore.delete(documentFilter);
            vectorStore.add(
                    chunks.stream()
                            .map(MerchantAiDocumentTextExtractor
                                    .PreparedChunk::vectorDocument)
                            .toList()
            );

            try {
                stateService.replaceChunksAndMarkReady(
                        source.getId(),
                        source.getAssistantId(),
                        source.getMerchantId(),
                        chunks
                );
            } catch (Exception persistenceException) {
                /* MySQL 未发布 READY 时，不能留下可能被召回的孤儿向量。 */
                deleteVectorsQuietly(vectorStore, documentFilter);
                throw persistenceException;
            }
        } catch (Exception exception) {
            String reason = safeFailureReason(exception);
            log.error(
                    "处理商家导购文档失败，documentId={}, merchantId={}",
                    documentId,
                    merchantId,
                    exception
            );
            stateService.markFailed(documentId, merchantId, reason);
        }
    }

    private Filter.Expression documentFilter(Long documentId) {
        return new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("document_id"),
                new Filter.Value(documentId.toString())
        );
    }

    private void deleteVectorsQuietly(
            VectorStore vectorStore,
            Filter.Expression filter
    ) {
        try {
            vectorStore.delete(filter);
        } catch (Exception cleanupException) {
            log.error("清理未发布的导购文档向量失败", cleanupException);
        }
    }

    private String safeFailureReason(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getMessage();
        }
        return "导购文档解析或向量化失败，请检查服务后重试";
    }
}
