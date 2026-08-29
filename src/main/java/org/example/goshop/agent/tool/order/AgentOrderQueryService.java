package org.example.goshop.agent.tool.order;

import lombok.RequiredArgsConstructor;
import org.example.goshop.order.dto.OrderItemSummaryResponse;
import org.example.goshop.order.dto.OrderSummaryResponse;
import org.example.goshop.order.service.OrderService;
import org.example.goshop.product.dto.PageResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.order.dto.OrderSafeDetailItemResponse;
import org.example.goshop.order.dto.OrderSafeDetailResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Agent 订单只读查询门面。
 *
 * <p>该 Service 只调用 OrderService，不直接访问 MallOrderMapper
 * 或 OrderItemMapper。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentOrderQueryService {

    private final OrderService orderService;

    /**
     * 用于把历史 SKU 规格 JSON 转成结构化 Map。
     */
    private final ObjectMapper objectMapper;

    /**
     * 单个订单最多向模型发送 30 条商品记录。
     *
     * <p>正常商城订单通常远小于该值。设置上限可以防止异常历史订单
     * 占用过大的模型上下文。</p>
     */
    private static final int MAX_DETAIL_ITEMS = 30;

    /**
     * 单个规格 JSON 最大字符数。
     */
    private static final int MAX_SPEC_JSON_CHARS = 2000;

    /**
     * 商品图片地址最大字符数。
     */
    private static final int MAX_IMAGE_URL_CHARS = 1000;

    /**
     * 查询当前登录买家的订单摘要。
     *
     * @param userId 只能来自服务端 AgentToolRequestContext
     * @param filter 模型可选的订单状态枚举
     * @param limit  服务端限制为 1～10
     */
    public AgentOrderListResult listOrders(
            Long userId,
            AgentOrderStatusFilter filter,
            int limit
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "Agent 订单查询缺少合法用户 ID"
            );
        }

        AgentOrderStatusFilter effectiveFilter =
                filter == null
                        ? AgentOrderStatusFilter.ALL
                        : filter;

        PageResult<OrderSummaryResponse> page =
                orderService.listUserOrderSummaries(
                        userId,
                        effectiveFilter.businessValue(),
                        limit
                );

        List<AgentOrderSummary> orders =
                page.records()
                        .stream()
                        .map(this::toAgentOrder)
                        .toList();

        return new AgentOrderListResult(
                orders,
                page.total(),
                page.total() > orders.size(),
                effectiveFilter
        );
    }

    private AgentOrderSummary toAgentOrder(
            OrderSummaryResponse source
    ) {
        List<AgentOrderItemSummary> items =
                source.items()
                        .stream()
                        .map(this::toAgentOrderItem)
                        .toList();

        return new AgentOrderSummary(
                source.orderNo(),
                normalizeOrderStatus(
                        source.status()
                ),
                source.totalAmountCent(),
                source.payAmountCent(),
                source.expireAt(),
                source.paidAt(),
                source.shippedAt(),
                source.createdAt(),
                items,
                source.itemLineCount(),
                source.itemsTruncated()
        );
    }

    private AgentOrderItemSummary toAgentOrderItem(
            OrderItemSummaryResponse source
    ) {
        return new AgentOrderItemSummary(
                source.productId(),
                source.skuId(),
                normalizeText(
                        source.productTitle(),
                        200
                ),
                source.productImage(),
                source.unitPriceCent(),
                source.quantity(),
                source.subtotalCent()
        );
    }

    /**
     * 只允许稳定的订单状态值进入模型。
     *
     * <p>如果数据库中出现未知历史状态，则返回 UNKNOWN，
     * 不把任意数据库文本直接交给模型。</p>
     */
    private String normalizeOrderStatus(
            String status
    ) {
        if (!StringUtils.hasText(status)) {
            return "UNKNOWN";
        }

        return switch (status.strip()) {
            case "PENDING_PAYMENT",
                 "WAITING_SHIPMENT",
                 "WAITING_RECEIPT",
                 "COMPLETED",
                 "CANCELLED",
                 "REFUNDING",
                 "REFUNDED" ->
                    status.strip();

            default -> "UNKNOWN";
        };
    }

    /**
     * 订单商品标题是下单时保存的商家文本快照，
     * 仍然属于不可信业务数据。
     */
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

    /**
     * 查询当前登录买家的隐私安全订单详情。
     *
     * @param userId  只能来自服务端 AgentToolRequestContext
     * @param orderNo 模型从 list_orders 结果或用户消息中取得的订单号
     */
    public AgentOrderDetailResult getOrderDetail(
            Long userId,
            String orderNo
    ) {
        /*
         * Agent 查询层再次验证可信上下文中的 userId。
         */
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "Agent 订单详情查询缺少合法用户 ID"
            );
        }

        /*
         * orderNo 是模型可填写参数，不能直接信任。
         */
        if (!StringUtils.hasText(orderNo)
                || orderNo.length() > 64) {
            throw new IllegalArgumentException(
                    "订单号不合法"
            );
        }

        String normalizedOrderNo =
                orderNo.strip();

        /*
         * OrderService 内部会再次通过 userId + orderNo 校验归属，
         * 并使用不查询 address_snapshot_json 的安全 SQL。
         */
        OrderSafeDetailResponse source =
                orderService.getUserOrderSafeDetail(
                        userId,
                        normalizedOrderNo
                );

        List<OrderSafeDetailItemResponse> sourceItems =
                source.items() == null
                        ? List.of()
                        : source.items();

        /*
         * 先转换全部商品并计算完整统计，再截断发送给模型的部分。
         */
        List<AgentOrderDetailItem> allItems =
                sourceItems.stream()
                        .map(this::toAgentOrderDetailItem)
                        .toList();

        long totalQuantity =
                allItems.stream()
                        .mapToLong(
                                AgentOrderDetailItem::quantity
                        )
                        .sum();

        List<AgentOrderDetailItem> visibleItems =
                allItems.stream()
                        .limit(MAX_DETAIL_ITEMS)
                        .toList();

        return new AgentOrderDetailResult(
                source.orderNo(),
                normalizeOrderStatus(
                        source.status()
                ),
                source.totalAmountCent(),
                source.payAmountCent(),
                source.expireAt(),
                source.paidAt(),

                /*
                 * 物流公司可能由商家填写，因此同样限制长度，
                 * 并继续把它视为不可信业务文本。
                 */
                normalizeText(
                        source.shippingCompany(),
                        100
                ),

                /*
                 * 物流单号属于当前买家自己的订单信息，可以返回给本人，
                 * 但不能写入普通日志和工具审计摘要。
                 */
                normalizeText(
                        source.trackingNo(),
                        100
                ),

                source.shippedAt(),
                source.createdAt(),
                visibleItems,
                allItems.size(),
                totalQuantity,
                allItems.size() > MAX_DETAIL_ITEMS
        );
    }

    /**
     * 将订单模块的安全商品 DTO 转成 Agent DTO。
     */
    private AgentOrderDetailItem toAgentOrderDetailItem(
            OrderSafeDetailItemResponse source
    ) {
        return new AgentOrderDetailItem(
                source.spuId(),
                source.skuId(),

                /*
                 * 商品标题是商家历史文本，限制长度但不能视为可信指令。
                 */
                normalizeText(
                        source.productTitle(),
                        200
                ),

                normalizeText(
                        source.productImage(),
                        MAX_IMAGE_URL_CHARS
                ),

                parseSpecifications(
                        source.specsJson()
                ),

                source.unitPriceCent(),
                source.quantity(),
                source.subtotalCent()
        );
    }

    /**
     * 把历史订单中的 SKU 规格 JSON 转成结构化 Map。
     *
     * <p>不能直接把原始 JSON 字符串交给模型，否则会出现双重转义，
     * 也不利于限制异常内容。</p>
     */
    private Map<String, Object> parseSpecifications(
            String specsJson
    ) {
        if (!StringUtils.hasText(specsJson)) {
            return Map.of();
        }

        /*
         * 数据库中的历史异常内容不能无限进入模型上下文。
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
             * 解析失败时不返回原始 JSON，也不暴露异常正文。
             * 订单的其他字段仍然可以正常返回。
             */
            return Map.of(
                    "specificationUnavailable",
                    true
            );
        }
    }
}
