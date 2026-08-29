package org.example.goshop.merchant.ai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiDocumentProperties;
import org.example.goshop.merchant.ai.config.MerchantAiRagProperties;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentListQuery;
import org.example.goshop.merchant.ai.dto.UploadMerchantAiDocumentRequest;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.ai.mapper.MerchantAiDocumentMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.service.MerchantService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 商家智能导购文档业务服务。 */
@Service
@RequiredArgsConstructor
public class MerchantAiDocumentService {

    private static final String UPLOADED_STATUS = "UPLOADED";

    private final MerchantService merchantService;
    private final MerchantAiAssistantMapper assistantMapper;
    private final MerchantAiDocumentMapper documentMapper;
    private final MerchantAiDocumentStorageService storageService;
    private final MerchantAiDocumentProperties properties;
    private final MerchantAiRagProperties ragProperties;
    private final MerchantAiRagAvailabilityService ragAvailabilityService;
    private final MerchantAiDocumentProcessingLauncher processingLauncher;
    private final MerchantAiVectorCleanupService vectorCleanupService;

    /**
     * 为当前登录商家的助手上传一份导购文档。
     *
     * <p>处理顺序严格固定：恢复 JWT 商家身份 → 校验助手配置 →
     * 校验文件并计算摘要 → 内容去重与数量限制 → 私有 OSS 上传 →
     * 元数据落库。任何数据库回滚都会删除本次新上传的 OSS 对象。</p>
     */
    @Transactional
    public MerchantAiDocumentResponse uploadCurrentDocument(
            Long merchantUserId,
            UploadMerchantAiDocumentRequest request
    ) {
        MerchantProfileResponse merchant =
                merchantService.getCurrentMerchantProfile(
                        merchantUserId
                );
        MerchantAiAssistant assistant =
                assistantMapper.selectByMerchantId(
                        merchant.id()
                );
        if (assistant == null) {
            throw new BusinessException(
                    40901,
                    "请先保存智能导购助手配置"
            );
        }

        MerchantAiDocumentStorageService.PreparedDocument prepared =
                storageService.prepare(request.file());

        /* 相同内容重复上传时直接返回原文档，不重复占用 OSS 和向量资源。 */
        MerchantAiDocument existing =
                documentMapper.selectByMerchantAndSha256(
                        merchant.id(),
                        prepared.sha256()
                );
        if (existing != null) {
            return MerchantAiDocumentResponse.from(existing);
        }

        long currentCount = documentMapper.countByAssistantId(
                assistant.getId()
        );
        if (currentCount
                >= properties.maxDocumentsPerAssistant()) {
            throw new BusinessException(
                    40901,
                    "导购文档数量已达到上限"
            );
        }

        Long documentId = IdWorker.getId();
        MerchantAiDocumentStorageService.UploadResult upload =
                storageService.upload(
                        merchant.id(),
                        documentId,
                        prepared
                );
        registerRollbackCleanup(upload.objectKey());

        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);
        MerchantAiDocument document = new MerchantAiDocument();
        document.setId(documentId);
        document.setAssistantId(assistant.getId());
        document.setMerchantId(merchant.id());
        document.setOriginalFilename(
                prepared.originalFilename()
        );
        document.setObjectKey(upload.objectKey());
        document.setFileType(prepared.fileType());
        document.setMimeType(prepared.mimeType());
        document.setSizeBytes(prepared.sizeBytes());
        document.setSha256(prepared.sha256());
        document.setStatus(UPLOADED_STATUS);
        document.setFailureReason(null);
        document.setChunkCount(0);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);

        int inserted;
        try {
            inserted = documentMapper.insert(document);
        } catch (DuplicateKeyException exception) {
            /* 并发上传同一内容时唯一键兜底；当前对象由回滚回调清理。 */
            throw new BusinessException(
                    40901,
                    "相同内容的导购文档已上传"
            );
        }
        if (inserted != 1) {
            throw new BusinessException(
                    50000,
                    "保存导购文档元数据失败"
            );
        }

        return MerchantAiDocumentResponse.from(document);
    }

    /**
     * 分页查询当前登录商家的导购文档和处理状态。
     *
     * <p>merchantId 仍由 JWT 对应店铺恢复，前端无法传入其他店铺 ID。
     * Mapper SQL 再次携带 merchant_id 作为最终租户隔离条件。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<MerchantAiDocumentResponse> listCurrentDocuments(
            Long merchantUserId,
            MerchantAiDocumentListQuery query
    ) {
        MerchantProfileResponse merchant =
                merchantService.getCurrentMerchantProfile(
                        merchantUserId
                );

        IPage<MerchantAiDocument> documentPage =
                documentMapper.selectDocumentPage(
                        new Page<>(
                                query.effectivePage(),
                                query.effectivePageSize()
                        ),
                        merchant.id(),
                        query.normalizedStatus()
                );

        List<MerchantAiDocumentResponse> records =
                documentPage.getRecords()
                        .stream()
                        .map(MerchantAiDocumentResponse::from)
                        .toList();

        return new PageResult<>(
                records,
                documentPage.getCurrent(),
                documentPage.getSize(),
                documentPage.getTotal()
        );
    }

    /**
     * 删除当前登录商家拥有的一份导购文档。
     *
     * <p>文档行使用 FOR UPDATE 锁定，且查询、删除 SQL 都包含
     * merchant_id。PROCESSING 文档不能删除，避免解析任务仍在读取原文件。
     * 数据库提交成功后才删除私有 OSS；回滚时原文件仍然保留。</p>
     */
    @Transactional
    public void deleteCurrentDocument(
            Long merchantUserId,
            Long documentId
    ) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(
                    40001,
                    "文档ID必须为正数"
            );
        }

        MerchantProfileResponse merchant =
                merchantService.getCurrentMerchantProfile(
                        merchantUserId
                );
        MerchantAiDocument document =
                documentMapper.selectOwnedDocumentForUpdate(
                        documentId,
                        merchant.id()
                );
        if (document == null) {
            /* 不区分不存在与属于其他商家，避免通过 ID 探测他店文档。 */
            throw new BusinessException(
                    40401,
                    "导购文档不存在"
            );
        }
        if ("PROCESSING".equals(document.getStatus())) {
            throw new BusinessException(
                    40901,
                    "导购文档正在解析，暂时不能删除"
            );
        }

        registerAfterCommitDelete(
                document.getId(),
                document.getObjectKey()
        );

        int deleted = documentMapper.deleteOwnedDocument(
                documentId,
                merchant.id()
        );
        if (deleted != 1) {
            throw new BusinessException(
                    50000,
                    "删除导购文档元数据失败"
            );
        }
    }

    /**
     * 认领当前商家的文档并在事务提交后启动异步解析。
     *
     * <p>UPLOADED、FAILED 与 READY 都可以进入处理；READY 重建用于让
     * 已上传文档应用新的分片或 Embedding 策略。近期的 PROCESSING 会被
     * 拒绝，超过超时时间的 PROCESSING 可重新认领，用于应用重启或工作
     * 线程异常退出后的人工恢复。</p>
     */
    @Transactional
    public MerchantAiDocumentResponse processCurrentDocument(
            Long merchantUserId,
            Long documentId
    ) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(40001, "文档ID必须为正数");
        }
        MerchantProfileResponse merchant =
                merchantService.getCurrentMerchantProfile(
                        merchantUserId
                );
        MerchantAiDocument document =
                documentMapper.selectOwnedDocumentForUpdate(
                        documentId,
                        merchant.id()
                );
        if (document == null) {
            throw new BusinessException(40401, "导购文档不存在");
        }
        LocalDateTime now = LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime staleBefore = now.minus(
                ragProperties.processingTimeout()
        );
        if ("PROCESSING".equals(document.getStatus())
                && (document.getUpdatedAt() == null
                || !document.getUpdatedAt().isBefore(staleBefore))) {
            throw new BusinessException(
                    40901,
                    "导购文档正在解析，请勿重复提交"
            );
        }

        /* 在修改数据库前验证外部组件，配置缺失时保持原状态便于稍后重试。 */
        ragAvailabilityService.requireAvailable();
        if (documentMapper.markProcessing(
                documentId,
                merchant.id(),
                now,
                staleBefore
        ) != 1) {
            throw new BusinessException(
                    40901,
                    "导购文档状态已变化，请刷新后重试"
            );
        }

        document.setStatus("PROCESSING");
        document.setFailureReason(null);
        document.setChunkCount(0);
        document.setUpdatedAt(now);
        registerAfterCommitProcessing(documentId, merchant.id());
        return MerchantAiDocumentResponse.from(document);
    }

    /**
     * 事务成功提交后删除私有 OSS 原文件。
     *
     * <p>OSS 删除失败不回滚已经提交的数据库事务，由孤儿对象清理任务
     * 后续兜底；提前删除则可能在数据库回滚后造成文档永久丢失。</p>
     */
    private void registerAfterCommitDelete(
            Long documentId,
            String objectKey
    ) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            throw new BusinessException(
                    50000,
                    "导购文档删除事务状态异常"
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        storageService.deleteQuietly(objectKey);
                        vectorCleanupService
                                .deleteDocumentVectorsQuietly(
                                        documentId
                                );
                    }
                }
        );
    }

    /** 数据库成功提交 PROCESSING 后才允许后台线程读取该任务。 */
    private void registerAfterCommitProcessing(
            Long documentId,
            Long merchantId
    ) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            throw new BusinessException(
                    50000,
                    "导购文档处理事务状态异常"
            );
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        processingLauncher.launch(
                                documentId,
                                merchantId
                        );
                    }
                }
        );
    }

    /**
     * 注册事务回滚后的 OSS 清理。
     *
     * <p>必须在上传完成后、数据库写入前注册，才能覆盖插入异常以及
     * 最终 commit 失败。若事务同步意外未开启，则立即清理并终止请求。</p>
     */
    private void registerRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            storageService.deleteQuietly(objectKey);
            throw new BusinessException(
                    50000,
                    "导购文档上传事务状态异常"
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status
                                != TransactionSynchronization.STATUS_COMMITTED) {
                            storageService.deleteQuietly(objectKey);
                        }
                    }
                }
        );
    }
}
