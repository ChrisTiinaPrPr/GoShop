package org.example.goshop.agent.tool.product;

import lombok.RequiredArgsConstructor;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.product.dto.ProductListResponse;
import org.example.goshop.product.dto.ProductSort;
import org.example.goshop.product.service.ProductService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.product.mapper.ProductDetailResponse;
import org.example.goshop.product.mapper.ProductSkuResponse;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Agent 商品查询门面。
 *
 * <p>它只负责把商品模块的公开结果转换成 Agent 专用 DTO，
 * 不直接访问 Mapper，也不复制商品可见性 SQL。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentProductQueryService {

    private final ProductService productService;
    private final ObjectMapper objectMapper;

    /**
     * 防止一个商品包含大量 SKU，导致模型上下文急剧增长。
     */
    private static final int MAX_DETAIL_SKUS = 20;

    /**
     * 商品描述进入模型前的最大字符数。
     */
    private static final int MAX_DESCRIPTION_CHARS = 2000;

    /**
     * 单个 SKU 规格 JSON 的最大字符数。
     */
    private static final int MAX_SPEC_JSON_CHARS = 2000;

    public AgentProductSearchResult search(
            String keyword,
            Long categoryId,
            Long minPriceCent,
            Long maxPriceCent,
            ProductSort sort,
            int limit
    ) {
        PageResult<ProductListResponse> page =
                productService
                        .searchPublicProductsForAgent(
                                keyword,
                                categoryId,
                                minPriceCent,
                                maxPriceCent,
                                sort,
                                limit
                        );

        List<AgentProductSearchItem> items =
                page.records()
                        .stream()
                        .map(AgentProductSearchItem::from)
                        .toList();

        return new AgentProductSearchResult(
                items,
                page.total(),
                page.total() > items.size()
        );
    }

    /**
     * 查询一个已上架商品的公开详情。
     *
     * <p>商品可见性、SKU 状态和可用库存计算全部复用 ProductService，
     * Agent 层不直接访问商品 Mapper。</p>
     */
    public AgentProductDetailResult getDetail(
            Long productId
    ) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "商品 ID 必须为正数"
            );
        }

        /*
         * 该业务方法已经保证：
         *
         * 1. SPU 必须已上架；
         * 2. 只返回已启用 SKU；
         * 3. availableStock = stock - lockedStock；
         * 4. 不存在、已下架或没有可用 SKU 时返回统一业务错误。
         */
        ProductDetailResponse source =
                productService.getPublicProductDetail(
                        productId
                );

        List<ProductSkuResponse> sourceSkus =
                source.skus() == null
                        ? List.of()
                        : source.skus();

        List<AgentProductSkuDetail> agentSkus =
                sourceSkus.stream()
                        /*
                         * ProductService 已按照价格、SKU ID 排序。
                         * 因此截断后仍能稳定返回价格最低的一组 SKU。
                         */
                        .limit(MAX_DETAIL_SKUS)
                        .map(this::toAgentSku)
                        .toList();

        return new AgentProductDetailResult(
                source.id(),
                source.categoryId(),
                normalizeText(source.title(), 200),
                normalizeText(
                        source.description(),
                        MAX_DESCRIPTION_CHARS
                ),
                source.mainImage(),
                source.salesCount(),
                agentSkus,
                sourceSkus.size() > MAX_DETAIL_SKUS
        );
    }

    /**
     * 把商品模块的 SKU DTO 转换成 Agent 专用 DTO。
     */
    private AgentProductSkuDetail toAgentSku(
            ProductSkuResponse source
    ) {
        return new AgentProductSkuDetail(
                source.id(),
                parseSpecifications(
                        source.specsJson()
                ),
                source.priceCent(),
                source.availableStock()
        );
    }

    /**
     * 把数据库中的 SKU 规格 JSON 转换成结构化 Map。
     *
     * <p>结构化对象比把 JSON 字符串再次嵌入工具结果更容易被模型理解，
     * 也避免出现双重 JSON 转义。</p>
     */
    private Map<String, Object> parseSpecifications(
            String specsJson
    ) {
        if (!StringUtils.hasText(specsJson)) {
            return Map.of();
        }

        /*
         * 商品规格属于商家可编辑数据。
         * 即使数据库字段异常变得很长，也不能无限送入模型上下文。
         */
        if (specsJson.length()
                > MAX_SPEC_JSON_CHARS) {
            return Map.of(
                    "specificationUnavailable",
                    true
            );
        }

        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(
                            specsJson,
                            new TypeReference<
                                    LinkedHashMap<
                                            String,
                                            Object
                                            >
                                    >() {
                            }
                    );

            return parsed == null
                    ? Map.of()
                    : parsed;
        } catch (Exception exception) {
            /*
             * 规格 JSON 损坏时不返回原始文本，也不把解析异常交给模型。
             * 商品其他字段仍然可以正常展示。
             */
            return Map.of(
                    "specificationUnavailable",
                    true
            );
        }
    }

    /**
     * 清理并限制进入模型上下文的商家文本。
     *
     * <p>这里的限制只控制上下文大小，不代表文本是可信指令。
     * 系统提示词仍必须明确把商品描述视为不可信业务数据。</p>
     */
    private String normalizeText(
            String value,
            int maxChars
    ) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.strip();

        if (normalized.length() <= maxChars) {
            return normalized;
        }

        return normalized.substring(0, maxChars);
    }
}
