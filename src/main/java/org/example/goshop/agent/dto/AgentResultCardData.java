package org.example.goshop.agent.dto;

import org.example.goshop.agent.tool.order.AgentOrderDetailItem;
import org.example.goshop.agent.tool.order.AgentOrderDetailResult;
import org.example.goshop.agent.tool.order.AgentOrderItemSummary;
import org.example.goshop.agent.tool.order.AgentOrderListResult;
import org.example.goshop.agent.tool.order.AgentOrderSummary;
import org.example.goshop.agent.tool.product.AgentProductDetailResult;
import org.example.goshop.agent.tool.product.AgentProductSearchItem;
import org.example.goshop.agent.tool.product.AgentProductSearchResult;
import org.example.goshop.agent.tool.product.AgentProductSkuDetail;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 工具结果对应的安全展示卡片。
 *
 * <p>该 DTO 只能由服务端根据白名单工具的结构化返回值创建，不能接收模型
 * 自由生成的 JSON。它不包含收货人、手机号、地址、成本价、原始库存或
 * 工具审计参数。</p>
 */
public record AgentResultCardData(
        String toolCallId,
        String cardType,
        List<ProductCard> products,
        List<OrderCard> orders,
        long total,
        boolean hasMore
) {

    public static final String PRODUCT_LIST = "PRODUCT_LIST";
    public static final String PRODUCT_DETAIL = "PRODUCT_DETAIL";
    public static final String ORDER_LIST = "ORDER_LIST";
    public static final String ORDER_DETAIL = "ORDER_DETAIL";

    public AgentResultCardData {
        products = products == null ? List.of() : List.copyOf(products);
        orders = orders == null ? List.of() : List.copyOf(orders);

        if (!List.of(
                PRODUCT_LIST,
                PRODUCT_DETAIL,
                ORDER_LIST,
                ORDER_DETAIL
        ).contains(cardType)) {
            throw new IllegalArgumentException("Agent 结果卡片类型不合法");
        }
        if (total < 0) {
            throw new IllegalArgumentException("Agent 结果卡片总数不能为负数");
        }

        boolean productCard = cardType.startsWith("PRODUCT_");
        if (productCard && !orders.isEmpty()) {
            throw new IllegalArgumentException("商品卡片不能包含订单数据");
        }
        if (!productCard && !products.isEmpty()) {
            throw new IllegalArgumentException("订单卡片不能包含商品数据");
        }
    }

    /** 将商品搜索工具结果压缩为最多 10 张公开商品卡片。 */
    public static AgentResultCardData fromProductSearch(
            AgentProductSearchResult result
    ) {
        Objects.requireNonNull(result, "商品搜索结果不能为空");

        List<ProductCard> cards = result.items().stream()
                .map(AgentResultCardData::fromSearchItem)
                .toList();

        return new AgentResultCardData(
                null,
                PRODUCT_LIST,
                cards,
                List.of(),
                result.total(),
                result.hasMore()
        );
    }

    /** 将商品详情工具结果转换为一张包含公开 SKU 的商品卡片。 */
    public static AgentResultCardData fromProductDetail(
            AgentProductDetailResult result
    ) {
        Objects.requireNonNull(result, "商品详情结果不能为空");

        List<ProductSkuCard> skus = result.skus().stream()
                .map(AgentResultCardData::fromSku)
                .toList();

        long minPriceCent = result.skus().stream()
                .mapToLong(AgentProductSkuDetail::priceCent)
                .min()
                .orElse(0L);

        ProductCard card = new ProductCard(
                result.productId(),
                result.title(),
                result.mainImage(),
                minPriceCent,
                result.salesCount(),
                skus,
                result.skusTruncated()
        );

        return new AgentResultCardData(
                null,
                PRODUCT_DETAIL,
                List.of(card),
                List.of(),
                1,
                false
        );
    }

    /** 将本人订单列表转换为不含地址和联系方式的订单卡片。 */
    public static AgentResultCardData fromOrderList(
            AgentOrderListResult result
    ) {
        Objects.requireNonNull(result, "订单列表结果不能为空");

        List<OrderCard> cards = result.orders().stream()
                .map(AgentResultCardData::fromOrderSummary)
                .toList();

        return new AgentResultCardData(
                null,
                ORDER_LIST,
                List.of(),
                cards,
                result.total(),
                result.hasMore()
        );
    }

    /** 将本人订单详情转换为一张订单卡片，不包含收货地址快照。 */
    public static AgentResultCardData fromOrderDetail(
            AgentOrderDetailResult result
    ) {
        Objects.requireNonNull(result, "订单详情结果不能为空");

        List<OrderItemCard> items = result.items().stream()
                .map(AgentResultCardData::fromOrderDetailItem)
                .toList();

        OrderCard card = new OrderCard(
                result.orderNo(),
                result.status(),
                result.payAmountCent(),
                result.createdAt(),
                items,
                result.itemLineCount(),
                result.itemsTruncated(),
                result.shippingCompany(),
                result.trackingNo()
        );

        return new AgentResultCardData(
                null,
                ORDER_DETAIL,
                List.of(),
                List.of(card),
                1,
                false
        );
    }

    /**
     * 把卡片绑定到服务端生成的工具调用 ID，供 SSE 去重和历史恢复使用。
     */
    public AgentResultCardData bindToolCallId(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("工具调用 ID 不合法");
        }

        return new AgentResultCardData(
                value.strip(),
                cardType,
                products,
                orders,
                total,
                hasMore
        );
    }

    private static ProductCard fromSearchItem(
            AgentProductSearchItem item
    ) {
        return new ProductCard(
                item.productId(),
                item.title(),
                item.mainImage(),
                item.minPriceCent(),
                item.salesCount(),
                List.of(),
                false
        );
    }

    private static ProductSkuCard fromSku(
            AgentProductSkuDetail sku
    ) {
        return new ProductSkuCard(
                sku.skuId(),
                displaySpecifications(sku.specifications()),
                sku.priceCent(),
                sku.availableStock()
        );
    }

    private static OrderCard fromOrderSummary(
            AgentOrderSummary order
    ) {
        return new OrderCard(
                order.orderNo(),
                order.status(),
                order.payAmountCent(),
                order.createdAt(),
                order.items().stream()
                        .map(AgentResultCardData::fromOrderItemSummary)
                        .toList(),
                order.itemLineCount(),
                order.itemsTruncated(),
                null,
                null
        );
    }

    private static OrderItemCard fromOrderItemSummary(
            AgentOrderItemSummary item
    ) {
        return new OrderItemCard(
                item.productId(),
                item.skuId(),
                item.productTitle(),
                item.productImage(),
                item.quantity(),
                item.subtotalCent(),
                null
        );
    }

    private static OrderItemCard fromOrderDetailItem(
            AgentOrderDetailItem item
    ) {
        return new OrderItemCard(
                item.productId(),
                item.skuId(),
                item.productTitle(),
                item.productImage(),
                item.quantity(),
                item.subtotalCent(),
                displaySpecifications(item.specifications())
        );
    }

    /**
     * 规格 Map 只转换为简短纯文本；Vue 会继续按文本转义展示。
     */
    private static String displaySpecifications(
            Map<String, Object> specifications
    ) {
        if (specifications == null || specifications.isEmpty()) {
            return "默认规格";
        }

        String text = specifications.entrySet().stream()
                .map(entry -> entry.getKey() + "：" + entry.getValue())
                .collect(Collectors.joining(" / "));

        return text.length() <= 200
                ? text
                : text.substring(0, 200);
    }

    /** 公开商品卡片。价格单位始终为分，避免浮点金额误差。 */
    public record ProductCard(
            Long productId,
            String title,
            String imageUrl,
            Long minPriceCent,
            Long salesCount,
            List<ProductSkuCard> skus,
            boolean skusTruncated
    ) {
        public ProductCard {
            skus = skus == null ? List.of() : List.copyOf(skus);
        }
    }

    /** 商品详情卡片中的公开 SKU。 */
    public record ProductSkuCard(
            Long skuId,
            String specificationText,
            Long priceCent,
            Integer availableStock
    ) {
    }

    /** 当前买家的订单卡片，不包含收货人、手机号和地址。 */
    public record OrderCard(
            String orderNo,
            String status,
            Long payAmountCent,
            LocalDateTime createdAt,
            List<OrderItemCard> items,
            int itemLineCount,
            boolean itemsTruncated,
            String shippingCompany,
            String trackingNo
    ) {
        public OrderCard {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 订单卡片中的商品快照。 */
    public record OrderItemCard(
            Long productId,
            Long skuId,
            String title,
            String imageUrl,
            Integer quantity,
            Long subtotalCent,
            String specificationText
    ) {
    }
}
