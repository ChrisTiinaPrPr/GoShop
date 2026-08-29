package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiRagProperties;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 集中校验 RAG 开关、Embedding 与 Qdrant VectorStore 是否已经就绪。 */
@Service
@RequiredArgsConstructor
public class MerchantAiRagAvailabilityService {

    private final MerchantAiRagProperties properties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public void requireAvailable() {
        requireVectorStore();
    }

    /**
     * 校验 RAG 运行条件并返回唯一 VectorStore。
     *
     * <p>文档处理和知识检索共用此入口，防止不同调用方对开关、Embedding
     * 与 Qdrant 就绪状态作出不一致判断。</p>
     */
    public VectorStore requireVectorStore() {
        if (!properties.enabled()) {
            throw new BusinessException(
                    50301,
                    "智能导购知识库处理功能尚未启用"
            );
        }
        try {
            VectorStore vectorStore =
                    vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new BusinessException(
                        50301,
                        "Embedding 或 Qdrant 尚未配置"
                );
            }
            return vectorStore;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    50301,
                    "Qdrant 暂时不可用，请检查服务配置"
            );
        }
    }
}
