package org.example.goshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.order.entity.OrderItem;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 查询订单中的全部商品快照。
     *
     * order_item 中保存的是下单时的商品快照，
     * 即使商品以后改名、下架，聊天卡片仍然能够显示当时的信息。
     */
    @Select("""
            SELECT *
            FROM order_item
            WHERE order_id = #{orderId}
            ORDER BY id ASC
            """)
    List<OrderItem> selectByOrderId(@Param("orderId") Long orderId);
}
