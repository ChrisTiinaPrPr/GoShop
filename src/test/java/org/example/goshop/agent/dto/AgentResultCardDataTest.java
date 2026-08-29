package org.example.goshop.agent.dto;

import org.example.goshop.agent.tool.AgentToolEventChannel;
import org.example.goshop.agent.tool.order.AgentOrderDetailItem;
import org.example.goshop.agent.tool.order.AgentOrderDetailResult;
import org.example.goshop.agent.tool.order.AgentOrderItemSummary;
import org.example.goshop.agent.tool.order.AgentOrderListResult;
import org.example.goshop.agent.tool.order.AgentOrderStatusFilter;
import org.example.goshop.agent.tool.order.AgentOrderSummary;
import org.example.goshop.agent.tool.product.AgentProductDetailResult;
import org.example.goshop.agent.tool.product.AgentProductSearchItem;
import org.example.goshop.agent.tool.product.AgentProductSearchResult;
import org.example.goshop.agent.tool.product.AgentProductSkuDetail;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 商品与订单展示卡片的安全映射测试。
 */
class AgentResultCardDataTest {

    @Test
    void shouldMapProductSearchWithoutInventingSkuData() {
        AgentProductSearchResult source = new AgentProductSearchResult(
                List.of(new AgentProductSearchItem(
                        101L,
                        8L,
                        "无线耳机",
                        "https://image.example/101.jpg",
                        19900L,
                        35L
                )),
                3,
                true
        );

        AgentResultCardData card =
                AgentResultCardData.fromProductSearch(source);

        assertEquals(AgentResultCardData.PRODUCT_LIST, card.cardType());
        assertEquals(3, card.total());
        assertTrue(card.hasMore());
        assertEquals(101L, card.products().get(0).productId());
        assertEquals(19900L, card.products().get(0).minPriceCent());
        assertTrue(card.products().get(0).skus().isEmpty());
        assertTrue(card.orders().isEmpty());
    }

    @Test
    void shouldMapProductDetailSkuPriceAndAvailableStock() {
        AgentProductDetailResult source = new AgentProductDetailResult(
                101L,
                8L,
                "无线耳机",
                "公开描述",
                "https://image.example/101.jpg",
                35L,
                List.of(new AgentProductSkuDetail(
                        201L,
                        Map.of("颜色", "星云紫"),
                        20900L,
                        6
                )),
                false
        );

        AgentResultCardData card =
                AgentResultCardData.fromProductDetail(source);

        AgentResultCardData.ProductSkuCard sku =
                card.products().get(0).skus().get(0);
        assertEquals(AgentResultCardData.PRODUCT_DETAIL, card.cardType());
        assertEquals("颜色：星云紫", sku.specificationText());
        assertEquals(20900L, sku.priceCent());
        assertEquals(6, sku.availableStock());
    }

    @Test
    void shouldMapOrderListWithoutShippingOrAddressData() {
        LocalDateTime createdAt = LocalDateTime.of(
                2026, 8, 10, 20, 30
        );
        AgentOrderItemSummary item = new AgentOrderItemSummary(
                101L,
                201L,
                "无线耳机",
                "https://image.example/101.jpg",
                19900L,
                1,
                19900L
        );
        AgentOrderSummary order = new AgentOrderSummary(
                "YG20260810001",
                "WAITING_SHIPMENT",
                19900L,
                19900L,
                null,
                createdAt,
                null,
                createdAt,
                List.of(item),
                1,
                false
        );

        AgentResultCardData card = AgentResultCardData.fromOrderList(
                new AgentOrderListResult(
                        List.of(order),
                        1,
                        false,
                        AgentOrderStatusFilter.ALL
                )
        );

        AgentResultCardData.OrderCard mapped = card.orders().get(0);
        assertEquals(AgentResultCardData.ORDER_LIST, card.cardType());
        assertEquals("YG20260810001", mapped.orderNo());
        assertEquals(19900L, mapped.payAmountCent());
        assertNull(mapped.shippingCompany());
        assertNull(mapped.trackingNo());

        List<String> publicFields = Arrays.stream(
                        AgentResultCardData.OrderCard.class
                                .getRecordComponents()
                )
                .map(component -> component.getName().toLowerCase())
                .toList();
        assertFalse(publicFields.contains("address"));
        assertFalse(publicFields.contains("phone"));
        assertFalse(publicFields.contains("receiver"));
    }

    @Test
    void shouldMapOrderDetailWithSafeLogisticsAndSpecifications() {
        LocalDateTime createdAt = LocalDateTime.of(
                2026, 8, 10, 20, 30
        );
        AgentOrderDetailResult source = new AgentOrderDetailResult(
                "YG20260810001",
                "WAITING_RECEIPT",
                19900L,
                19900L,
                null,
                createdAt,
                "顺丰速运",
                "SF123456",
                createdAt,
                createdAt,
                List.of(new AgentOrderDetailItem(
                        101L,
                        201L,
                        "无线耳机",
                        "https://image.example/101.jpg",
                        Map.of("颜色", "星云紫"),
                        19900L,
                        1,
                        19900L
                )),
                1,
                1,
                false
        );

        AgentResultCardData.OrderCard order =
                AgentResultCardData.fromOrderDetail(source)
                        .orders().get(0);

        assertEquals("顺丰速运", order.shippingCompany());
        assertEquals("SF123456", order.trackingNo());
        assertEquals(
                "颜色：星云紫",
                order.items().get(0).specificationText()
        );
    }

    @Test
    void shouldAttachStructuredCardOnlyToSuccessfulToolEvent() {
        AgentToolEventChannel channel =
                new AgentToolEventChannel(1L, 2L);
        AgentResultCardData card = AgentResultCardData.fromProductSearch(
                new AgentProductSearchResult(List.of(), 0, false)
        ).bindToolCallId("call-1");
        List<AgentSseEvent<?>> events = new ArrayList<>();

        /*
         * 先订阅再发布，验证单次运行的 unicast 通道实际输出扩展后的
         * TOOL_COMPLETED 数据，不额外引入 reactor-test 依赖。
         */
        channel.events().subscribe(events::add);
        channel.publishCompleted(
                "call-1",
                "search_products",
                true,
                "商品搜索完成",
                card
        );
        channel.complete();

        assertEquals(1, events.size());
        AgentSseData.ToolCompletedData data =
                (AgentSseData.ToolCompletedData) events.get(0).data();
        assertTrue(data.success());
        assertEquals(card, data.resultCard());
    }
}
