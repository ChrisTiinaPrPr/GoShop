package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 异步处理器的成功收口与失败状态测试。 */
@ExtendWith(MockitoExtension.class)
class MerchantAiDocumentProcessorTest {

    @Mock
    private MerchantAiDocumentMapper documentMapper;
    @Mock
    private MerchantAiDocumentStorageService storageService;
    @Mock
    private MerchantAiDocumentTextExtractor textExtractor;
    @Mock
    private MerchantAiDocumentProcessingStateService stateService;
    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;
    @Mock
    private VectorStore vectorStore;

    @InjectMocks
    private MerchantAiDocumentProcessor processor;

    @Test
    void shouldWriteVectorsThenPublishReadyChunks() {
        MerchantAiDocument source = processingDocument();
        byte[] bytes = new byte[]{1, 2, 3};
        Document vectorDocument = new Document(
                "e4c82a24-36c9-3c7b-8f3a-6241f2387865",
                "适合办公的静音键盘",
                Map.of("document_id", "9001")
        );
        MerchantAiDocumentTextExtractor.PreparedChunk chunk =
                new MerchantAiDocumentTextExtractor.PreparedChunk(
                        0,
                        vectorDocument.getId(),
                        vectorDocument.getText(),
                        "hash",
                        vectorDocument
                );

        when(documentMapper.selectOwnedDocument(9001L, 7001L))
                .thenReturn(source);
        when(storageService.download(7001L, source.getObjectKey()))
                .thenReturn(bytes);
        when(textExtractor.extract(source, bytes))
                .thenReturn(List.of(chunk));
        when(vectorStoreProvider.getIfAvailable())
                .thenReturn(vectorStore);

        processor.process(9001L, 7001L);

        verify(vectorStore).delete(any(
                org.springframework.ai.vectorstore.filter.Filter.Expression.class
        ));
        verify(vectorStore).add(List.of(vectorDocument));
        verify(stateService).replaceChunksAndMarkReady(
                9001L,
                8001L,
                7001L,
                List.of(chunk)
        );
        verify(stateService, never()).markFailed(any(), any(), any());
    }

    @Test
    void shouldMarkFailedWhenDocumentCannotBeParsed() {
        MerchantAiDocument source = processingDocument();
        byte[] bytes = new byte[]{1, 2, 3};
        when(documentMapper.selectOwnedDocument(9001L, 7001L))
                .thenReturn(source);
        when(storageService.download(7001L, source.getObjectKey()))
                .thenReturn(bytes);
        when(textExtractor.extract(source, bytes))
                .thenThrow(new BusinessException(42201, "文档已损坏"));

        processor.process(9001L, 7001L);

        verify(stateService).markFailed(
                9001L,
                7001L,
                "文档已损坏"
        );
        verify(vectorStoreProvider, never()).getIfAvailable();
        verify(vectorStore, never()).add(any());
    }

    private MerchantAiDocument processingDocument() {
        MerchantAiDocument source = new MerchantAiDocument();
        source.setId(9001L);
        source.setAssistantId(8001L);
        source.setMerchantId(7001L);
        source.setObjectKey("merchant-ai/documents/7001/guide.txt");
        source.setOriginalFilename("guide.txt");
        source.setStatus("PROCESSING");
        return source;
    }
}
