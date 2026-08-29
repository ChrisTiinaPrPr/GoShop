package org.example.goshop.review.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.review.dto.CreateProductReviewRequest;
import org.example.goshop.review.dto.OrderItemReviewItem;
import org.example.goshop.review.dto.ProductReviewListItem;
import org.example.goshop.review.dto.ProductReviewPageResponse;
import org.example.goshop.review.dto.ProductReviewSummary;
import org.example.goshop.review.dto.ReviewableOrderItem;
import org.example.goshop.review.entity.ProductReview;
import org.example.goshop.review.mapper.ProductReviewMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品评价业务测试，覆盖订单归属、状态机、唯一评价和公开列表映射。
 */
@SuppressWarnings("unchecked")
class ProductReviewServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2001L;
    private static final Long ORDER_ITEM_ID = 3001L;
    private static final Long PRODUCT_ID = 4001L;

    private ProductReviewMapper reviewMapper;
    private MallOrderMapper orderMapper;
    private ProductReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewMapper = mock(ProductReviewMapper.class);
        orderMapper = mock(MallOrderMapper.class);
        reviewService = new ProductReviewService(reviewMapper, orderMapper);
    }

    @Test
    void shouldCreateReviewFromTrustedCompletedOrderItem() {
        ReviewableOrderItem orderItem = reviewableOrderItem("COMPLETED");
        when(reviewMapper.selectReviewableOrderItemForUpdate(ORDER_ITEM_ID, USER_ID))
                .thenReturn(orderItem);
        when(reviewMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(reviewMapper.insert(any(ProductReview.class))).thenReturn(1);

        var response = reviewService.createReview(
                USER_ID,
                new CreateProductReviewRequest(ORDER_ITEM_ID, 5, "  很满意  ")
        );

        assertEquals(ORDER_ITEM_ID, response.orderItemId());
        assertEquals(PRODUCT_ID, response.productId());
        assertEquals("很满意", response.content());

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewMapper).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(PRODUCT_ID, captor.getValue().getSpuId());
    }

    @Test
    void shouldHideOrderItemOwnership() {
        when(reviewMapper.selectReviewableOrderItemForUpdate(ORDER_ITEM_ID, USER_ID))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reviewService.createReview(
                        USER_ID,
                        new CreateProductReviewRequest(ORDER_ITEM_ID, 5, null)
                )
        );

        assertEquals(40401, exception.getCode());
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void shouldRejectReviewBeforeOrderCompleted() {
        when(reviewMapper.selectReviewableOrderItemForUpdate(ORDER_ITEM_ID, USER_ID))
                .thenReturn(reviewableOrderItem("WAITING_RECEIPT"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reviewService.createReview(
                        USER_ID,
                        new CreateProductReviewRequest(ORDER_ITEM_ID, 4, null)
                )
        );

        assertEquals(40901, exception.getCode());
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void shouldRejectDuplicateOrderItemReview() {
        when(reviewMapper.selectReviewableOrderItemForUpdate(ORDER_ITEM_ID, USER_ID))
                .thenReturn(reviewableOrderItem("COMPLETED"));
        when(reviewMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reviewService.createReview(
                        USER_ID,
                        new CreateProductReviewRequest(ORDER_ITEM_ID, 4, "再次评价")
                )
        );

        assertEquals(40901, exception.getCode());
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void shouldListOwnedOrderReviewState() {
        MallOrder order = new MallOrder();
        order.setId(ORDER_ID);
        order.setStatus("COMPLETED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        OrderItemReviewItem unreviewed = new OrderItemReviewItem();
        unreviewed.setOrderItemId(ORDER_ITEM_ID);
        unreviewed.setSpuId(PRODUCT_ID);
        when(reviewMapper.selectOrderItemReviews(ORDER_ID)).thenReturn(List.of(unreviewed));

        var result = reviewService.listOrderReviews(USER_ID, "YG-2001");

        assertEquals(1, result.size());
        assertFalse(result.get(0).reviewed());
        assertTrue(result.get(0).reviewable());
    }

    @Test
    void shouldListPublicReviewsWithSummary() {
        when(reviewMapper.existsPublicProduct(PRODUCT_ID)).thenReturn(true);

        ProductReviewListItem item = new ProductReviewListItem();
        item.setId(5001L);
        item.setScore(5);
        item.setReviewerNickname("测试买家");
        item.setCreatedAt(LocalDateTime.of(2026, 8, 10, 12, 0));
        Page<ProductReviewListItem> mapperPage = new Page<>(1, 10, 1);
        mapperPage.setRecords(List.of(item));
        when(reviewMapper.selectPublicReviewPage(any(Page.class), eq(PRODUCT_ID)))
                .thenReturn(mapperPage);

        ProductReviewSummary summary = new ProductReviewSummary();
        summary.setReviewCount(1L);
        summary.setAverageScore(4.8D);
        when(reviewMapper.selectPublicReviewSummary(PRODUCT_ID)).thenReturn(summary);

        ProductReviewPageResponse result =
                reviewService.listPublicProductReviews(PRODUCT_ID, 1, 10);

        assertEquals(1, result.total());
        assertEquals(4.8D, result.averageScore());
        assertEquals("测试买家", result.records().get(0).reviewerNickname());
    }

    @Test
    void shouldRejectPublicReviewsForInvisibleProduct() {
        when(reviewMapper.existsPublicProduct(PRODUCT_ID)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reviewService.listPublicProductReviews(PRODUCT_ID, 1, 10)
        );

        assertEquals(40401, exception.getCode());
        verify(reviewMapper, never()).selectPublicReviewPage(any(Page.class), eq(PRODUCT_ID));
        assertNull(exception.getCause());
    }

    private ReviewableOrderItem reviewableOrderItem(String status) {
        ReviewableOrderItem item = new ReviewableOrderItem();
        item.setOrderItemId(ORDER_ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setSpuId(PRODUCT_ID);
        item.setOrderStatus(status);
        return item;
    }
}
