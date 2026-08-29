package org.example.goshop.merchant.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiDocumentListQuery;
import org.example.goshop.merchant.ai.dto.MerchantAiAssistantResponse;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchRequest;
import org.example.goshop.merchant.ai.dto.MerchantAiKnowledgeSearchResponse;
import org.example.goshop.merchant.ai.dto.SaveMerchantAiAssistantRequest;
import org.example.goshop.merchant.ai.dto.UploadMerchantAiDocumentRequest;
import org.example.goshop.merchant.ai.service.MerchantAiDocumentService;
import org.example.goshop.merchant.ai.service.MerchantAiAssistantService;
import org.example.goshop.merchant.ai.service.MerchantAiKnowledgeSearchService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家智能导购助手管理接口。
 */
@Tag(name = "商家智能导购助手")
@RestController
@RequestMapping("/api/v1/merchant/ai-assistant")
@RequiredArgsConstructor
@Validated
public class MerchantAiAssistantController {

    private final MerchantAiAssistantService assistantService;
    private final MerchantAiDocumentService documentService;
    private final MerchantAiKnowledgeSearchService knowledgeSearchService;

    /**
     * 查询当前商家的助手配置或默认预览。
     *
     * <p>商家账户 ID 来自已经验证的 JWT，接口不接收 merchantId。</p>
     */
    @GetMapping
    @Operation(summary = "查询当前商家的智能导购助手配置")
    public Result<MerchantAiAssistantResponse> getAssistant(
            Authentication authentication
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                assistantService.getCurrentAssistant(
                        merchantUserId
                )
        );
    }

    /**
     * 创建或完整更新当前商家的助手配置。
     *
     * <p>PUT 具有幂等语义：同一商家重复提交相同请求只会保留一条配置。
     * merchantId 始终由 JWT 对应的商家身份确定。</p>
     */
    @PutMapping
    @Operation(summary = "创建或更新当前商家的智能导购助手配置")
    public Result<MerchantAiAssistantResponse> saveAssistant(
            Authentication authentication,
            @Valid @RequestBody
            SaveMerchantAiAssistantRequest request
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                assistantService.saveCurrentAssistant(
                        merchantUserId,
                        request
                )
        );
    }

    /**
     * 上传当前商家的导购知识文档。
     *
     * <p>文件只保存到私有 OSS，响应不返回 objectKey 或下载地址。
     * 上传完成状态为 UPLOADED，后续解析任务再推进为 READY 或 FAILED。</p>
     */
    @PostMapping(
            value = "/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(summary = "上传当前商家的智能导购文档")
    public Result<MerchantAiDocumentResponse> uploadDocument(
            Authentication authentication,
            @Valid @ModelAttribute
            UploadMerchantAiDocumentRequest request
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                documentService.uploadCurrentDocument(
                        merchantUserId,
                        request
                )
        );
    }

    /** 查询当前商家的导购文档及其解析状态。 */
    @GetMapping("/documents")
    @Operation(summary = "分页查询当前商家的智能导购文档")
    public Result<PageResult<MerchantAiDocumentResponse>> listDocuments(
            Authentication authentication,
            @Valid @ModelAttribute
            MerchantAiDocumentListQuery query
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                documentService.listCurrentDocuments(
                        merchantUserId,
                        query
                )
        );
    }

    /** 删除当前商家拥有的导购文档。 */
    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "删除当前商家的智能导购文档")
    public Result<Void> deleteDocument(
            Authentication authentication,
            @PathVariable
            @Positive(message = "文档ID必须为正数")
            Long documentId
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        documentService.deleteCurrentDocument(
                merchantUserId,
                documentId
        );
        return Result.ok();
    }

    /** 启动、重试或重建当前商家导购文档的异步解析与 Qdrant 入库。 */
    @PostMapping("/documents/{documentId}/process")
    @Operation(summary = "开始、重试或重新解析智能导购文档")
    public Result<MerchantAiDocumentResponse> processDocument(
            Authentication authentication,
            @PathVariable
            @Positive(message = "文档ID必须为正数")
            Long documentId
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                documentService.processCurrentDocument(
                        merchantUserId,
                        documentId
                )
        );
    }

    /**
     * 供商家验证自己知识库的语义召回结果。
     *
     * <p>接口不接收 merchantId 或 assistantId，两者都由当前 JWT 商家身份
     * 恢复，避免商家构造参数检索其他店铺的私有导购资料。</p>
     */
    @PostMapping("/knowledge/search")
    @Operation(summary = "测试当前商家的智能导购知识库检索")
    public Result<MerchantAiKnowledgeSearchResponse> searchKnowledge(
            Authentication authentication,
            @Valid @RequestBody
            MerchantAiKnowledgeSearchRequest request
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                knowledgeSearchService.searchCurrentKnowledge(
                        merchantUserId,
                        request
                )
        );
    }
}
