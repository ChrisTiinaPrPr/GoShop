package org.example.goshop.merchant.ai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiDocumentProperties;
import org.example.goshop.merchant.ai.config.MerchantAiRagProperties;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentListQuery;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentResponse;
import org.example.goshop.merchant.ai.dto.UploadMerchantAiDocumentRequest;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.service.MerchantService;
import org.example.goshop.product.dto.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家导购文档上传业务测试。 */
@ExtendWith(MockitoExtension.class)
class MerchantAiDocumentServiceTest {

    @Mock
    private MerchantService merchantService;
    @Mock
    private MerchantAiAssistantMapper assistantMapper;
    @Mock
    private MerchantAiDocumentMapper documentMapper;
    @Mock
    private MerchantAiDocumentStorageService storageService;
    @Mock
    private MerchantAiDocumentProperties properties;
    @Mock
    private MerchantAiRagProperties ragProperties;
    @Mock
    private MerchantAiRagAvailabilityService ragAvailabilityService;
    @Mock
    private MerchantAiDocumentProcessingLauncher processingLauncher;
    @Mock
    private MerchantAiVectorCleanupService vectorCleanupService;

    @InjectMocks
    private MerchantAiDocumentService documentService;

    @BeforeEach
    void openTransactionSynchronization() {
        /* 单元测试直接调用 Service，因此手动模拟 @Transactional 同步环境。 */
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransactionSynchronization() {
        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    @Test
    void shouldUploadAndSaveDocumentForCurrentMerchant() {
        long userId = 1101L;
        long merchantId = 7101L;
        MerchantAiAssistant assistant = assistant(8101L, merchantId);
        MerchantAiDocumentStorageService.PreparedDocument prepared =
                prepared("guide.pdf", "hash-1");

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(assistant);
        when(storageService.prepare(any())).thenReturn(prepared);
        when(documentMapper.selectByMerchantAndSha256(
                merchantId,
                "hash-1"
        )).thenReturn(null);
        when(documentMapper.countByAssistantId(
                assistant.getId()
        )).thenReturn(2L);
        when(properties.maxDocumentsPerAssistant())
                .thenReturn(20);
        when(storageService.upload(
                org.mockito.ArgumentMatchers.eq(merchantId),
                any(Long.class),
                org.mockito.ArgumentMatchers.eq(prepared)
        )).thenReturn(
                new MerchantAiDocumentStorageService.UploadResult(
                        "merchant-ai/documents/7101/object"
                )
        );
        when(documentMapper.insert(
                any(MerchantAiDocument.class)
        )).thenReturn(1);

        MerchantAiDocumentResponse response =
                documentService.uploadCurrentDocument(
                        userId,
                        request()
                );

        ArgumentCaptor<MerchantAiDocument> captor =
                ArgumentCaptor.forClass(
                        MerchantAiDocument.class
                );
        verify(documentMapper).insert(captor.capture());
        MerchantAiDocument saved = captor.getValue();
        assertEquals(merchantId, saved.getMerchantId());
        assertEquals(assistant.getId(), saved.getAssistantId());
        assertEquals("hash-1", saved.getSha256());
        assertEquals("UPLOADED", saved.getStatus());
        assertEquals(0, saved.getChunkCount());
        assertEquals(saved.getId(), response.id());
        assertEquals("guide.pdf", response.originalFilename());

        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );
        verify(storageService, never()).deleteQuietly(any());
    }

    @Test
    void shouldReturnExistingDocumentForSameContent() {
        long userId = 1102L;
        long merchantId = 7102L;
        MerchantAiAssistant assistant = assistant(8102L, merchantId);
        MerchantAiDocumentStorageService.PreparedDocument prepared =
                prepared("same.txt", "same-hash");
        MerchantAiDocument existing = document(
                9102L,
                assistant.getId(),
                merchantId,
                "same.txt",
                "same-hash"
        );

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(assistant);
        when(storageService.prepare(any())).thenReturn(prepared);
        when(documentMapper.selectByMerchantAndSha256(
                merchantId,
                "same-hash"
        )).thenReturn(existing);

        MerchantAiDocumentResponse response =
                documentService.uploadCurrentDocument(
                        userId,
                        request()
                );

        assertEquals(9102L, response.id());
        verify(storageService, never()).upload(
                any(),
                any(),
                any()
        );
        verify(documentMapper, never())
                .countByAssistantId(any());
        verify(documentMapper, never()).insert(
                any(MerchantAiDocument.class)
        );
    }

    @Test
    void shouldRejectUploadBeforeAssistantConfigured() {
        long userId = 1103L;
        long merchantId = 7103L;
        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(null);

        assertThrows(
                BusinessException.class,
                () -> documentService.uploadCurrentDocument(
                        userId,
                        request()
                )
        );

        verify(storageService, never()).prepare(any());
        verify(documentMapper, never()).insert(
                any(MerchantAiDocument.class)
        );
    }

    @Test
    void shouldRejectUploadWhenDocumentLimitReached() {
        long userId = 1104L;
        long merchantId = 7104L;
        MerchantAiAssistant assistant = assistant(8104L, merchantId);
        MerchantAiDocumentStorageService.PreparedDocument prepared =
                prepared("limit.md", "hash-limit");

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(assistant);
        when(storageService.prepare(any())).thenReturn(prepared);
        when(documentMapper.selectByMerchantAndSha256(
                merchantId,
                "hash-limit"
        )).thenReturn(null);
        when(documentMapper.countByAssistantId(
                assistant.getId()
        )).thenReturn(20L);
        when(properties.maxDocumentsPerAssistant())
                .thenReturn(20);

        assertThrows(
                BusinessException.class,
                () -> documentService.uploadCurrentDocument(
                        userId,
                        request()
                )
        );

        verify(storageService, never()).upload(
                any(),
                any(),
                any()
        );
        verify(documentMapper, never()).insert(
                any(MerchantAiDocument.class)
        );
    }

    @Test
    void shouldDeleteUploadedObjectWhenDatabaseInsertFails() {
        long userId = 1105L;
        long merchantId = 7105L;
        MerchantAiAssistant assistant = assistant(8105L, merchantId);
        MerchantAiDocumentStorageService.PreparedDocument prepared =
                prepared("race.pdf", "hash-race");
        String objectKey =
                "merchant-ai/documents/7105/race-object";

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(assistant);
        when(storageService.prepare(any())).thenReturn(prepared);
        when(documentMapper.selectByMerchantAndSha256(
                merchantId,
                "hash-race"
        )).thenReturn(null);
        when(documentMapper.countByAssistantId(
                assistant.getId()
        )).thenReturn(0L);
        when(properties.maxDocumentsPerAssistant())
                .thenReturn(20);
        when(storageService.upload(
                org.mockito.ArgumentMatchers.eq(merchantId),
                any(Long.class),
                org.mockito.ArgumentMatchers.eq(prepared)
        )).thenReturn(
                new MerchantAiDocumentStorageService.UploadResult(
                        objectKey
                )
        );
        when(documentMapper.insert(
                any(MerchantAiDocument.class)
        ))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThrows(
                BusinessException.class,
                () -> documentService.uploadCurrentDocument(
                        userId,
                        request()
                )
        );

        completeSynchronization(
                TransactionSynchronization.STATUS_ROLLED_BACK
        );
        verify(storageService).deleteQuietly(objectKey);
    }

    @Test
    void shouldListOnlyCurrentMerchantDocumentsByStatus() {
        long userId = 1106L;
        long merchantId = 7106L;
        MerchantAiDocument failed = document(
                9106L,
                8106L,
                merchantId,
                "failed.pdf",
                "failed-hash"
        );
        failed.setStatus("FAILED");
        failed.setFailureReason("PDF 文档无法解析");

        Page<MerchantAiDocument> mapperPage =
                new Page<>(2, 10, 1);
        mapperPage.setRecords(List.of(failed));
        mapperPage.setTotal(1);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(documentMapper.selectDocumentPage(
                any(),
                eq(merchantId),
                eq("FAILED")
        )).thenReturn(mapperPage);

        PageResult<MerchantAiDocumentResponse> response =
                documentService.listCurrentDocuments(
                        userId,
                        new MerchantAiDocumentListQuery(
                                2L,
                                10L,
                                " failed "
                        )
                );

        assertEquals(2L, response.page());
        assertEquals(10L, response.pageSize());
        assertEquals(1L, response.total());
        assertEquals(1, response.records().size());
        assertEquals(
                "PDF 文档无法解析",
                response.records().get(0).failureReason()
        );
        verify(documentMapper).selectDocumentPage(
                any(),
                eq(merchantId),
                eq("FAILED")
        );
    }

    @Test
    void shouldNotListDocumentsWhenMerchantIdentityInvalid() {
        long userId = 1107L;
        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenThrow(
                new BusinessException(
                        40301,
                        "商家不存在或已停用"
                )
        );

        assertThrows(
                BusinessException.class,
                () -> documentService.listCurrentDocuments(
                        userId,
                        new MerchantAiDocumentListQuery(
                                null,
                                null,
                                null
                        )
                )
        );

        verify(documentMapper, never()).selectDocumentPage(
                any(),
                any(),
                any()
        );
    }

    @Test
    void shouldDeleteOwnedDocumentAndCleanObjectAfterCommit() {
        long userId = 1108L;
        long merchantId = 7108L;
        long documentId = 9108L;
        String objectKey =
                "merchant-ai/documents/7108/delete-object";
        MerchantAiDocument document = document(
                documentId,
                8108L,
                merchantId,
                "delete.pdf",
                "delete-hash"
        );
        document.setObjectKey(objectKey);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(document);
        when(documentMapper.deleteOwnedDocument(
                documentId,
                merchantId
        )).thenReturn(1);

        documentService.deleteCurrentDocument(
                userId,
                documentId
        );

        verify(documentMapper).deleteOwnedDocument(
                documentId,
                merchantId
        );
        /* 数据库事务提交前绝不能提前删除原文件。 */
        verify(storageService, never()).deleteQuietly(objectKey);

        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );
        verify(storageService).deleteQuietly(objectKey);
        verify(vectorCleanupService)
                .deleteDocumentVectorsQuietly(documentId);
    }

    @Test
    void shouldKeepObjectWhenDeleteTransactionRollsBack() {
        long userId = 1109L;
        long merchantId = 7109L;
        long documentId = 9109L;
        String objectKey =
                "merchant-ai/documents/7109/rollback-object";
        MerchantAiDocument document = document(
                documentId,
                8109L,
                merchantId,
                "rollback.txt",
                "rollback-hash"
        );
        document.setObjectKey(objectKey);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(document);
        when(documentMapper.deleteOwnedDocument(
                documentId,
                merchantId
        )).thenReturn(1);

        documentService.deleteCurrentDocument(
                userId,
                documentId
        );
        completeSynchronization(
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        verify(storageService, never()).deleteQuietly(objectKey);
        verify(vectorCleanupService, never())
                .deleteDocumentVectorsQuietly(documentId);
    }

    @Test
    void shouldNotDeleteDocumentOwnedByAnotherMerchant() {
        long userId = 1110L;
        long merchantId = 7110L;
        long documentId = 9110L;

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> documentService.deleteCurrentDocument(
                        userId,
                        documentId
                )
        );

        assertEquals(40401, exception.getCode());
        verify(documentMapper, never()).deleteOwnedDocument(
                any(),
                any()
        );
        verify(storageService, never()).deleteQuietly(any());
    }

    @Test
    void shouldRejectDeletingProcessingDocument() {
        long userId = 1111L;
        long merchantId = 7111L;
        long documentId = 9111L;
        MerchantAiDocument processing = document(
                documentId,
                8111L,
                merchantId,
                "processing.docx",
                "processing-hash"
        );
        processing.setStatus("PROCESSING");

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(processing);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> documentService.deleteCurrentDocument(
                        userId,
                        documentId
                )
        );

        assertEquals(40901, exception.getCode());
        verify(documentMapper, never()).deleteOwnedDocument(
                any(),
                any()
        );
    }

    @Test
    void shouldMarkUploadedDocumentProcessingAndLaunchAfterCommit() {
        long userId = 1112L;
        long merchantId = 7112L;
        long documentId = 9112L;
        MerchantAiDocument document = document(
                documentId,
                8112L,
                merchantId,
                "guide.md",
                "process-hash"
        );
        document.setUpdatedAt(LocalDateTime.now().minusMinutes(1));

        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(document);
        when(ragProperties.processingTimeout())
                .thenReturn(Duration.ofMinutes(15));
        when(documentMapper.markProcessing(
                eq(documentId),
                eq(merchantId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);

        MerchantAiDocumentResponse response =
                documentService.processCurrentDocument(
                        userId,
                        documentId
                );

        assertEquals("PROCESSING", response.status());
        verify(ragAvailabilityService).requireAvailable();
        verify(processingLauncher, never()).launch(any(), any());

        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );
        verify(processingLauncher).launch(documentId, merchantId);
    }

    @Test
    void shouldAllowReadyDocumentToRebuildVectorIndex() {
        long userId = 1114L;
        long merchantId = 7114L;
        long documentId = 9114L;
        MerchantAiDocument document = document(
                documentId,
                8114L,
                merchantId,
                "ready-guide.md",
                "ready-hash"
        );
        document.setStatus("READY");
        document.setChunkCount(7);
        document.setUpdatedAt(LocalDateTime.now().minusMinutes(1));

        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(document);
        when(ragProperties.processingTimeout())
                .thenReturn(Duration.ofMinutes(15));
        when(documentMapper.markProcessing(
                eq(documentId),
                eq(merchantId),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);

        MerchantAiDocumentResponse response =
                documentService.processCurrentDocument(
                        userId,
                        documentId
                );

        assertEquals("PROCESSING", response.status());
        assertEquals(0, response.chunkCount());
        completeSynchronization(
                TransactionSynchronization.STATUS_COMMITTED
        );
        verify(processingLauncher).launch(documentId, merchantId);
    }

    @Test
    void shouldRejectDuplicateProcessingRequestBeforeCheckingQdrant() {
        long userId = 1113L;
        long merchantId = 7113L;
        long documentId = 9113L;
        MerchantAiDocument document = document(
                documentId,
                8113L,
                merchantId,
                "processing.txt",
                "processing-hash"
        );
        document.setStatus("PROCESSING");
        document.setUpdatedAt(LocalDateTime.now());

        when(merchantService.getCurrentMerchantProfile(userId))
                .thenReturn(merchant(merchantId));
        when(documentMapper.selectOwnedDocumentForUpdate(
                documentId,
                merchantId
        )).thenReturn(document);
        when(ragProperties.processingTimeout())
                .thenReturn(Duration.ofMinutes(15));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> documentService.processCurrentDocument(
                        userId,
                        documentId
                )
        );

        assertEquals(40901, exception.getCode());
        verify(ragAvailabilityService, never()).requireAvailable();
        verify(documentMapper, never()).markProcessing(
                any(), any(), any(), any()
        );
    }

    private void completeSynchronization(int status) {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager
                        .getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(
                    TransactionSynchronization::afterCommit
            );
        }
        synchronizations.forEach(
                synchronization ->
                        synchronization.afterCompletion(status)
        );
        TransactionSynchronizationManager.clearSynchronization();
    }

    private MerchantAiAssistant assistant(
            Long assistantId,
            Long merchantId
    ) {
        MerchantAiAssistant assistant =
                new MerchantAiAssistant();
        assistant.setId(assistantId);
        assistant.setMerchantId(merchantId);
        return assistant;
    }

    private MerchantProfileResponse merchant(Long merchantId) {
        return new MerchantProfileResponse(
                merchantId,
                "测试店铺",
                "https://img.test/store.png",
                null
        );
    }

    private UploadMerchantAiDocumentRequest request() {
        return new UploadMerchantAiDocumentRequest(
                new MockMultipartFile(
                        "file",
                        "guide.txt",
                        "text/plain",
                        "guide".getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private MerchantAiDocumentStorageService.PreparedDocument prepared(
            String filename,
            String sha256
    ) {
        return new MerchantAiDocumentStorageService.PreparedDocument(
                filename,
                filename.endsWith(".pdf") ? "PDF" : "TXT",
                filename.endsWith(".pdf")
                        ? "application/pdf"
                        : "text/plain",
                filename.endsWith(".pdf") ? "pdf" : "txt",
                5L,
                sha256,
                new byte[]{1, 2, 3, 4, 5}
        );
    }

    private MerchantAiDocument document(
            Long id,
            Long assistantId,
            Long merchantId,
            String filename,
            String sha256
    ) {
        MerchantAiDocument document = new MerchantAiDocument();
        document.setId(id);
        document.setAssistantId(assistantId);
        document.setMerchantId(merchantId);
        document.setOriginalFilename(filename);
        document.setFileType("TXT");
        document.setMimeType("text/plain");
        document.setSizeBytes(5L);
        document.setSha256(sha256);
        document.setStatus("UPLOADED");
        document.setChunkCount(0);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }
}
