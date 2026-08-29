package org.example.goshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.order.entity.MallOrder;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MallOrderMapper extends BaseMapper<MallOrder> {

    /**
     * 支付前锁定订单。
     * 防止用户并发发起余额支付、支付宝支付或者重复点击支付按钮
     */
    @Select("""
            SELECT *
            FROM mall_order
            WHERE order_no = #{orderNo}
            AND user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    MallOrder selectByOrderNoAndUserIdForUpdate(
            @Param("orderNo") String orderNo,
            @Param("userId") Long userId
    );

    /**
     * 分批查询已经超过付款截止时间的待付款订单
     * 这里只查询 ID，不在扫描阶段长时间持有数据库行锁
     */
    @Select("""
        SELECT id
        FROM mall_order
        WHERE status = 'PENDING_PAYMENT'
          AND expire_at IS NOT NULL
          AND expire_at <= #{now}
        ORDER BY expire_at ASC
        LIMIT #{limit}
        """)
    List<Long> selectExpiredPendingOrderIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /**
     * 取消前锁定订单
     * 余额支付和超时取消都会争抢同一行锁，最终只有一个操作成功
     */
    @Select("""
        SELECT *
        FROM mall_order
        WHERE id = #{orderId}
        FOR UPDATE
        """)
    MallOrder selectByIdForUpdate(@Param("orderId") Long orderId);

    /** 商家发货、审核退款前按订单归属加锁。 */
    @Select("""
        SELECT * FROM mall_order
        WHERE order_no = #{orderNo} AND merchant_id = #{merchantId}
        LIMIT 1 FOR UPDATE
        """)
    MallOrder selectByOrderNoAndMerchantIdForUpdate(
            @Param("orderNo") String orderNo,
            @Param("merchantId") Long merchantId
    );

    /**
     * 查询允许在指定聊天会话中发送的订单。
     *
     * 为什么同时校验 buyerUserId 和 merchantId：
     * 1. 防止用户把别人的订单发送到聊天中；
     * 2. 防止把 A 商家的订单发送给 B 商家；
     * 3. 买家端和商家端都可以复用这条校验。
     *
     * 这里不使用 FOR UPDATE，因为发送订单卡片只读取订单，
     * 不会修改订单状态。
     */
    @Select("""
        SELECT *
        FROM mall_order
        WHERE order_no = #{orderNo}
          AND user_id = #{buyerUserId}
          AND merchant_id = #{merchantId}
        LIMIT 1
        """)
    MallOrder selectChatOrder(
            @Param("orderNo") String orderNo,
            @Param("buyerUserId") Long buyerUserId,
            @Param("merchantId") Long merchantId
    );

    /**
     * 查询当前买家自己的订单，同时从 SQL 层排除地址快照。
     *
     * <p>不能使用 SELECT *，因为 mall_order 中包含
     * address_snapshot_json。即使 Service 最终没有返回该字段，
     * SELECT * 仍会让地址快照进入 Agent 查询调用链。</p>
     *
     * <p>order_no 和 user_id 必须同时作为查询条件，
     * 防止通过猜测订单号读取其他买家的订单。</p>
     */
    @Select("""
        SELECT id,
               order_no,
               user_id,
               merchant_id,
               status,
               total_amount_cent,
               pay_amount_cent,
               expire_at,
               paid_at,
               shipping_company,
               tracking_no,
               shipped_at,
               created_at
        FROM mall_order
        WHERE order_no = #{orderNo}
          AND user_id = #{userId}
        LIMIT 1
        """)
    MallOrder selectSafeDetailByOrderNoAndUserId(
            @Param("orderNo") String orderNo,
            @Param("userId") Long userId
    );
}
