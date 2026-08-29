package org.example.goshop.agent.tool.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.agent.dto.AgentResultCardData;
import org.example.goshop.agent.service.AgentToolAuditService;
import org.example.goshop.agent.service.AgentResultCardPersistenceService;
import org.example.goshop.agent.service.model.AgentToolCallHandle;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.product.dto.ProductSort;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供给模型调用的商品搜索工具。
 *
 * <p>模型只能传入商品筛选条件，不能传入 userId、runId，
 * 也不能控制 SQL、分页偏移量或超过服务端上限的返回数量。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class ProductSearchTool {

    private static final String TOOL_NAME =
            "search_products";

    private static final String STARTED_TEXT =
            "正在搜索符合条件的商品";

    private static final String SUCCEEDED_TEXT =
            "商品搜索完成";

    private static final String FAILED_TEXT =
            "商品搜索失败";

    private static final String FAILURE_CODE =
            "PRODUCT_SEARCH_FAILED";

    /**
     * 模型不传 limit 时使用 5，服务端业务层仍会执行最大 10 条校验。
     */
    private static final int DEFAULT_LIMIT = 5;

    private final AgentProductQueryService
            productQueryService;

    private final AgentToolAuditService
            toolAuditService;

    private final AgentResultCardPersistenceService
            resultCardPersistenceService;

    /**
     * 按关键词、分类、价格区间和排序方式搜索已上架商品。
     */
    @Tool(
            name = TOOL_NAME,
            description = """
                    搜索优购商城当前已上架且存在可用 SKU 的商品。
                    当用户要求查找、推荐、筛选或比较商城商品时，应调用此工具。

                    keyword 是商品标题关键词，可以为空。
                    categoryId 是商城分类 ID；只有用户明确提供或已有可信分类 ID 时才能填写。
                    minPriceCent 和 maxPriceCent 的单位都是人民币分：
                    例如用户说最低 100 元，应传 10000；
                    用户说最高 300 元，应传 30000。
                    sort 只能是 LATEST、SALES、PRICE_ASC、PRICE_DESC。
                    limit 最大为 10，不传时默认返回 5 件。

                    不得编造分类 ID，不得使用用户输入生成 SQL，
                    不得返回工具结果中不存在的商品、价格或销量。
                    """
    )
    public AgentProductSearchResult searchProducts(
            @ToolParam(
                    description = """
                            商品标题关键词，例如“机械键盘”或“无线耳机”。
                            用户未指定关键词时可以不传。
                            """,
                    required = false
            )
            String keyword,

            @ToolParam(
                    description = """
                            商城分类 ID，必须是正整数。
                            只有上下文中存在可信分类 ID 时才传入，否则省略。
                            """,
                    required = false
            )
            Long categoryId,

            @ToolParam(
                    description = """
                            最低 SKU 价格，单位为人民币分。
                            例如 100 元传 10000；没有最低价格要求时省略。
                            """,
                    required = false
            )
            Long minPriceCent,

            @ToolParam(
                    description = """
                            最高 SKU 价格，单位为人民币分。
                            例如 300 元传 30000；没有最高价格要求时省略。
                            """,
                    required = false
            )
            Long maxPriceCent,

            @ToolParam(
                    description = """
                            排序方式：
                            LATEST 表示最新上架；
                            SALES 表示销量从高到低；
                            PRICE_ASC 表示价格从低到高；
                            PRICE_DESC 表示价格从高到低。
                            不传时使用 LATEST。
                            """,
                    required = false
            )
            ProductSort sort,

            @ToolParam(
                    description = """
                            最多返回多少件商品，只能是 1～10。
                            不传时默认返回 5 件。
                            """,
                    required = false
            )
            Integer limit,

            /*
             * ToolContext 完全由服务端注入，不会出现在模型看到的 JSON Schema 中。
             */
            ToolContext toolContext
    ) {
        AgentToolRequestContext requestContext = null;
        AgentToolCallHandle handle = null;
        boolean successAuditCompleted = false;

        try {
            /*
             * 恢复服务端可信的用户、会话、运行和事件通道。
             * 即使商品搜索暂时不使用 userId，也必须验证它运行在合法 AgentRun 中。
             */
            requestContext =
                    AgentToolRequestContext.from(
                            toolContext
                    );

            ProductSort effectiveSort =
                    sort == null
                            ? ProductSort.LATEST
                            : sort;

            int effectiveLimit =
                    limit == null
                            ? DEFAULT_LIMIT
                            : limit;

            /*
             * 审计摘要只保存筛选条件，不保存完整用户问题。
             * LinkedHashMap 允许逐项加入非空值，也能保持 JSON 字段顺序稳定。
             */
            Map<String, Object> argumentsSummary =
                    buildArgumentsSummary(
                            keyword,
                            categoryId,
                            minPriceCent,
                            maxPriceCent,
                            effectiveSort,
                            effectiveLimit
                    );

            /*
             * start() 会再次检查：
             *
             * 1. 会话归属；
             * 2. AgentRun 状态；
             * 3. 工具白名单；
             * 4. 单次运行最大工具调用次数。
             */
            handle = toolAuditService.start(
                    requestContext,
                    TOOL_NAME,
                    argumentsSummary
            );

            requestContext.eventChannel()
                    .publishStarted(
                            handle.toolCallId(),
                            handle.toolName(),
                            STARTED_TEXT
                    );

            /*
             * 工具只能调用商品业务门面，不能直接访问 ProductSpuMapper。
             */
            AgentProductSearchResult result =
                    productQueryService.search(
                            keyword,
                            categoryId,
                            minPriceCent,
                            maxPriceCent,
                            effectiveSort,
                            effectiveLimit
                    );

            AgentResultCardData resultCard =
                    resultCardPersistenceService.append(
                            requestContext.runId(),
                            handle.toolCallId(),
                            AgentResultCardData
                                    .fromProductSearch(result)
                    );

            /*
             * 普通审计日志不保存商品标题和图片。
             * 商品标题属于商家可编辑的不可信文本，也可能非常长。
             */
            toolAuditService.completeSucceeded(
                    handle,
                    buildResultSummary(result)
            );

            successAuditCompleted = true;

            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            true,
                            SUCCEEDED_TEXT,
                            resultCard
                    );

            return result;
        } catch (RuntimeException exception) {
            if (handle != null
                    && !successAuditCompleted) {
                safelyCompleteFailed(handle);
                safelyPublishFailed(
                        requestContext,
                        handle
                );
            }

            /*
             * 不记录关键词、工具原始结果或异常正文，避免日志污染和敏感信息泄露。
             */
            log.warn(
                    "Agent 商品搜索工具执行失败，runId={}, failureType={}",
                    requestContext == null
                            ? null
                            : requestContext.runId(),
                    exception.getClass()
                            .getSimpleName()
            );

            /*
             * 不把 SQL、Mapper 或模型参数转换异常直接暴露给模型。
             */
            throw new BusinessException(
                    50301,
                    "商品搜索暂时不可用"
            );
        }
    }

    /**
     * 构建允许写入 agent_tool_call 的参数摘要。
     */
    private Map<String, Object> buildArgumentsSummary(
            String keyword,
            Long categoryId,
            Long minPriceCent,
            Long maxPriceCent,
            ProductSort sort,
            int limit
    ) {
        Map<String, Object> summary =
                new LinkedHashMap<>();

        if (keyword != null
                && !keyword.isBlank()) {
            /*
             * 审计中只保留截断关键词。
             * 即使上层校验被修改，也不能无限写入日志表。
             */
            String normalized = keyword.strip();

            summary.put(
                    "keyword",
                    normalized.length() <= 100
                            ? normalized
                            : normalized.substring(0, 100)
            );
        }

        if (categoryId != null) {
            summary.put("categoryId", categoryId);
        }

        if (minPriceCent != null) {
            summary.put(
                    "minPriceCent",
                    minPriceCent
            );
        }

        if (maxPriceCent != null) {
            summary.put(
                    "maxPriceCent",
                    maxPriceCent
            );
        }

        summary.put("sort", sort.name());
        summary.put("limit", limit);

        return summary;
    }

    /**
     * 只记录结果数量、商品 ID 和截断状态，不保存完整商品载荷。
     */
    private Map<String, Object> buildResultSummary(
            AgentProductSearchResult result
    ) {
        List<Long> productIds =
                result.items()
                        .stream()
                        .map(
                                AgentProductSearchItem
                                        ::productId
                        )
                        .toList();

        return Map.of(
                "resultCount",
                result.items().size(),
                "total",
                result.total(),
                "hasMore",
                result.hasMore(),
                "productIds",
                productIds
        );
    }

    private void safelyCompleteFailed(
            AgentToolCallHandle handle
    ) {
        try {
            toolAuditService.completeFailed(
                    handle,
                    FAILURE_CODE
            );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 商品搜索失败审计收口失败，runId={}, auditId={}",
                    handle.runId(),
                    handle.auditId()
            );
        }
    }

    private void safelyPublishFailed(
            AgentToolRequestContext requestContext,
            AgentToolCallHandle handle
    ) {
        if (requestContext == null) {
            return;
        }

        try {
            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            false,
                            FAILED_TEXT
                    );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 商品搜索失败事件发布失败，runId={}, toolCallId={}",
                    handle.runId(),
                    handle.toolCallId()
            );
        }
    }
}
