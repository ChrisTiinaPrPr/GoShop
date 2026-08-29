package org.example.goshop.agent.tool.cart;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.cart.dto.CartItemResponse;
import org.example.goshop.cart.service.CartService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 购物车只读查询门面。
 *
 * <p>该 Service 只能调用现有 CartService，不能直接读取 Redis。
 * userId 之后由 get_cart 工具从服务端 ToolContext 获取，
 * 不能成为模型可填写的工具参数。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentCartQueryService {

    /**
     * 防止购物车数据过多导致模型上下文过大。
     */
    private static final int MAX_CART_ITEMS = 30;

    private static final int MAX_SPEC_JSON_CHARS =
            2000;

    private final CartService cartService;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前登录买家的购物车。
     *
     * @param userId 只能来自服务端验证过的 AgentToolRequestContext
     */
    public AgentCartResult getCart(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "Agent 购物车查询缺少合法用户 ID"
            );
        }

        /*
         * CartService 会使用 cart:{userId} 查询 Redis，
         * 并重新读取商品和 SKU，计算最新价格、库存及有效状态。
         */
        List<CartItemResponse> sourceItems =
                cartService
                        .listCurrentUserCartItems(
                                userId
                        );

        if (sourceItems == null
                || sourceItems.isEmpty()) {
            return new AgentCartResult(
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    false
            );
        }

        /*
         * 先转换全部数据并计算完整统计，再截断发送给模型的列表。
         * 因此 truncated=true 时，统计数据仍代表完整购物车。
         */
        List<AgentCartItem> allItems =
                sourceItems.stream()
                        .map(this::toAgentCartItem)
                        .toList();

        long totalQuantity =
                allItems.stream()
                        .mapToLong(
                                AgentCartItem::quantity
                        )
                        .sum();

        long selectedTotalCent = 0L;
        int selectedValidItemCount = 0;

        for (AgentCartItem item : allItems) {
            /*
             * 只有有效、已勾选并且价格可用的商品才进入结算金额。
             */
            if (item.valid()
                    && item.selected()
                    && item.lineTotalCent()
                    != null) {
                selectedTotalCent =
                        Math.addExact(
                                selectedTotalCent,
                                item.lineTotalCent()
                        );

                selectedValidItemCount++;
            }
        }

        List<AgentCartItem> visibleItems =
                allItems.stream()
                        .limit(MAX_CART_ITEMS)
                        .toList();

        return new AgentCartResult(
                visibleItems,
                allItems.size(),
                totalQuantity,
                selectedTotalCent,
                selectedValidItemCount,
                allItems.size() > MAX_CART_ITEMS
        );
    }

    /**
     * 将现有购物车响应转换为 Agent 专用 DTO。
     */
    private AgentCartItem toAgentCartItem(
            CartItemResponse source
    ) {
        /*
         * Redis 中的旧数据可能没有 selected。
         * Boolean.TRUE.equals 可以安全地把 null 处理为 false。
         */
        boolean selected =
                Boolean.TRUE.equals(
                        source.selected()
                );

        boolean valid =
                Boolean.TRUE.equals(
                        source.valid()
                );

        Integer availableStock =
                source.availableStock() == null
                        ? 0
                        : Math.max(
                        source.availableStock(),
                        0
                );

        Long lineTotalCent = null;

        /*
         * SKU 被删除时 priceCent 可能为 null。
         * 不能让模型自行猜测该购物车项的价格。
         */
        if (source.priceCent() != null
                && source.priceCent() >= 0
                && source.quantity() != null
                && source.quantity() > 0) {
            lineTotalCent =
                    Math.multiplyExact(
                            source.priceCent(),
                            source.quantity()
                                    .longValue()
                    );
        }

        return new AgentCartItem(
                source.skuId(),
                source.spuId(),
                normalizeText(
                        source.title(),
                        200
                ),
                source.mainImage(),
                parseSpecifications(
                        source.specsJson()
                ),
                source.priceCent(),
                source.quantity(),
                selected,
                availableStock,
                valid,
                normalizeStatus(
                        source.status()
                ),
                lineTotalCent
        );
    }

    /**
     * 解析 SKU 规格 JSON。
     *
     * <p>规格属于商家可编辑的不可信数据；解析失败时不返回原始内容。</p>
     */
    private Map<String, Object> parseSpecifications(
            String specsJson
    ) {
        if (!StringUtils.hasText(specsJson)) {
            return Map.of();
        }

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
            return Map.of(
                    "specificationUnavailable",
                    true
            );
        }
    }

    /**
     * 购物车状态只允许稳定的服务端状态码。
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "UNKNOWN";
        }

        return switch (status.strip()) {
            case "NORMAL",
                 "SKU_NOT_FOUND",
                 "PRODUCT_NOT_FOUND",
                 "PRODUCT_OFF_SHELF",
                 "SKU_DISABLED",
                 "OUT_OF_STOCK",
                 "INSUFFICIENT_STOCK" ->
                    status.strip();

            default -> "UNKNOWN";
        };
    }

    private String normalizeText(
            String value,
            int maxChars
    ) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.strip();

        return normalized.length() <= maxChars
                ? normalized
                : normalized.substring(
                0,
                maxChars
        );
    }
}
