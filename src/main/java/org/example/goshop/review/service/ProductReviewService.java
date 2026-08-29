package org.example.goshop.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.review.dto.CreateProductReviewRequest;
import org.example.goshop.review.dto.OrderItemReviewItem;
import org.example.goshop.review.dto.OrderItemReviewResponse;
import org.example.goshop.review.dto.ProductReviewListItem;
import org.example.goshop.review.dto.ProductReviewPageResponse;
import org.example.goshop.review.dto.ProductReviewResponse;
import org.example.goshop.review.dto.ProductReviewSummary;
import org.example.goshop.review.dto.PublicProductReviewResponse;
import org.example.goshop.review.dto.ReviewableOrderItem;
import org.example.goshop.review.entity.ProductReview;
import org.example.goshop.review.mapper.ProductReviewMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductReviewService {

    private final ProductReviewMapper reviewMapper;
    private final MallOrderMapper orderMapper;

    /**
     * 为本人已完成订单中的一个订单项创建评价。
     *
     * <p>订单项归属、订单状态和商品 ID 都从数据库读取。锁定订单项后再检查唯一评价，
     * 可以让同一订单项的并发请求串行化，避免“先查询都不存在、随后重复插入”的竞态。</p>
     */
    @Transactional
    public ProductReviewResponse createReview(
            Long userId,
            CreateProductReviewRequest request
    ) {
        ReviewableOrderItem orderItem = reviewMapper.selectReviewableOrderItemForUpdate(
                request.orderItemId(),
                userId
        );

        if (orderItem == null) {
            throw new BusinessException(40401, "订单商品不存在或无权访问");
        }
        if (!"COMPLETED".equals(orderItem.getOrderStatus())) {
            throw new BusinessException(40901, "订单完成后才能评价商品");
        }

        Long existingCount = reviewMapper.selectCount(
                new LambdaQueryWrapper<ProductReview>()
                        .eq(ProductReview::getOrderItemId, request.orderItemId())
        );
        if (existingCount != null && existingCount > 0) {
            throw new BusinessException(40901, "该订单商品已经评价");
        }

        LocalDateTime now = LocalDateTime.now();
        ProductReview review = new ProductReview();
        review.setId(IdWorker.getId());
        review.setOrderItemId(request.orderItemId());
        review.setUserId(userId);
        review.setSpuId(orderItem.getSpuId());
        review.setScore(request.score());
        review.setContent(normalizeContent(request.content()));
        review.setStatus(1);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);

        if (reviewMapper.insert(review) != 1) {
            throw new BusinessException(50000, "评价保存失败，请稍后重试");
        }
        return ProductReviewResponse.from(review);
    }

    /**
     * 查询当前买家某个订单的商品评价状态。
     */
    @Transactional(readOnly = true)
    public List<OrderItemReviewResponse> listOrderReviews(Long userId, String orderNo) {
        MallOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<MallOrder>()
                        .eq(MallOrder::getOrderNo, orderNo)
                        .eq(MallOrder::getUserId, userId)
        );
        if (order == null) {
            throw new BusinessException(40401, "订单不存在或无权访问");
        }

        boolean completed = "COMPLETED".equals(order.getStatus());
        return reviewMapper.selectOrderItemReviews(order.getId())
                .stream()
                .map(item -> OrderItemReviewResponse.from(item, completed))
                .toList();
    }

    /**
     * 分页查询公开商品评价与实时评分摘要。
     */
    @Transactional(readOnly = true)
    public ProductReviewPageResponse listPublicProductReviews(
            Long productId,
            long page,
            long pageSize
    ) {
        if (!reviewMapper.existsPublicProduct(productId)) {
            throw new BusinessException(40401, "商品不存在或已下架");
        }

        IPage<ProductReviewListItem> reviewPage = reviewMapper.selectPublicReviewPage(
                new Page<>(page, pageSize),
                productId
        );
        ProductReviewSummary summary = reviewMapper.selectPublicReviewSummary(productId);

        List<PublicProductReviewResponse> records = reviewPage.getRecords()
                .stream()
                .map(PublicProductReviewResponse::from)
                .toList();

        double averageScore = summary == null || summary.getAverageScore() == null
                ? 0D
                : summary.getAverageScore();

        return new ProductReviewPageResponse(
                records,
                reviewPage.getCurrent(),
                reviewPage.getSize(),
                reviewPage.getTotal(),
                averageScore
        );
    }

    private String normalizeContent(String content) {
        return StringUtils.hasText(content) ? content.strip() : null;
    }
}
