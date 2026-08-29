package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 文档删除提交后，对 Qdrant 派生向量执行尽力清理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAiVectorCleanupService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * 清理失败不能回滚已经提交的 MySQL 删除事务。
     *
     * <p>后续检索层仍必须以 MySQL 中 READY 文档为最终有效性条件，这样即使
     * Qdrant 短暂离线留下孤儿点，也不会向买家返回已经删除的商家知识。</p>
     */
    public void deleteDocumentVectorsQuietly(Long documentId) {
        try {
            VectorStore vectorStore =
                    vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                return;
            }
            vectorStore.delete(new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("document_id"),
                    new Filter.Value(documentId.toString())
            ));
        } catch (Exception exception) {
            log.error(
                    "删除导购文档的 Qdrant 向量失败，documentId={}",
                    documentId,
                    exception
            );
        }
    }
}
