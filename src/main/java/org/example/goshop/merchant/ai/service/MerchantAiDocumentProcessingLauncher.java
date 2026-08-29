package org.example.goshop.merchant.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/** 把已经提交的文档处理任务投递到模块专用线程池。 */
@Slf4j
@Service
public class MerchantAiDocumentProcessingLauncher {

    private final Executor executor;
    private final MerchantAiDocumentProcessor processor;
    private final MerchantAiDocumentProcessingStateService stateService;

    /** 显式标注专用执行器，避免与 WebSocket 的多个 Executor Bean 混淆。 */
    public MerchantAiDocumentProcessingLauncher(
            @Qualifier("merchantAiDocumentExecutor") Executor executor,
            MerchantAiDocumentProcessor processor,
            MerchantAiDocumentProcessingStateService stateService
    ) {
        this.executor = executor;
        this.processor = processor;
        this.stateService = stateService;
    }

    /**
     * 仅由数据库事务 afterCommit 回调调用。
     *
     * <p>如果线程池已满，立即把任务置为 FAILED，使商家能够重试，而不是
     * 永久停留在 PROCESSING。</p>
     */
    public void launch(Long documentId, Long merchantId) {
        try {
            executor.execute(
                    () -> processor.process(documentId, merchantId)
            );
        } catch (RuntimeException exception) {
            log.error(
                    "提交商家导购文档处理任务失败，documentId={}",
                    documentId,
                    exception
            );
            stateService.markFailed(
                    documentId,
                    merchantId,
                    "文档处理任务繁忙，请稍后重试"
            );
        }
    }
}
