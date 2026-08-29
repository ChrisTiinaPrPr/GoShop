package org.example.goshop.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.review.dto.OrderItemReviewItem;
import org.example.goshop.review.dto.ProductReviewListItem;
import org.example.goshop.review.dto.ProductReviewSummary;
import org.example.goshop.review.dto.ReviewableOrderItem;
import org.example.goshop.review.entity.ProductReview;

import java.util.List;

@Mapper
public interface ProductReviewMapper extends BaseMapper<ProductReview> {

    /**
     * 按当前 JWT 买家锁定订单项。
     *
     * <p>FOR UPDATE 锁住 order_item 行，使同一订单项的并发评价请求串行执行；
     * user_id 条件同时防止通过猜测订单项 ID 评价他人的购买记录。</p>
     */
    @Select("""
            SELECT
                item.id AS order_item_id,
                item.order_id,
                item.spu_id,
                mall_order.status AS order_status
            FROM order_item item
            INNER JOIN mall_order ON mall_order.id = item.order_id
            WHERE item.id = #{orderItemId}
              AND mall_order.user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    ReviewableOrderItem selectReviewableOrderItemForUpdate(
            @Param("orderItemId") Long orderItemId,
            @Param("userId") Long userId
    );

    /**
     * 查询本人某个订单的全部订单项及其可选评价，商品信息始终使用下单快照。
     */
    @Select("""
            SELECT
                item.id AS order_item_id,
                item.spu_id,
                item.sku_id,
                item.product_title,
                item.product_image,
                item.specs_json,
                review.id AS review_id,
                review.score,
                review.content,
                review.created_at AS reviewed_at
            FROM order_item item
            LEFT JOIN product_review review
                ON review.order_item_id = item.id
            WHERE item.order_id = #{orderId}
            ORDER BY item.id ASC
            """)
    List<OrderItemReviewItem> selectOrderItemReviews(@Param("orderId") Long orderId);

    /**
     * 公开评价只返回 status=1 的记录；昵称为空时使用统一展示名，不暴露手机号等隐私字段。
     */
    @Select("""
            SELECT
                review.id,
                review.score,
                review.content,
                COALESCE(NULLIF(TRIM(sys_user.nickname), ''), '优购买家') AS reviewer_nickname,
                sys_user.avatar_url AS reviewer_avatar_url,
                item.specs_json,
                review.created_at
            FROM product_review review
            INNER JOIN sys_user ON sys_user.id = review.user_id
            INNER JOIN order_item item ON item.id = review.order_item_id
            WHERE review.spu_id = #{productId}
              AND review.status = 1
            ORDER BY review.created_at DESC, review.id DESC
            """)
    IPage<ProductReviewListItem> selectPublicReviewPage(
            Page<ProductReviewListItem> page,
            @Param("productId") Long productId
    );

    @Select("""
            SELECT
                COUNT(*) AS review_count,
                COALESCE(AVG(score), 0) AS average_score
            FROM product_review
            WHERE spu_id = #{productId}
              AND status = 1
            """)
    ProductReviewSummary selectPublicReviewSummary(@Param("productId") Long productId);

    /**
     * 公开评价入口与当前商品详情保持同一可见性规则。
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM product_spu spu
                INNER JOIN product_sku sku
                    ON sku.spu_id = spu.id
                   AND sku.status = 1
                WHERE spu.id = #{productId}
                  AND spu.status = 1
            )
            """)
    boolean existsPublicProduct(@Param("productId") Long productId);
}
