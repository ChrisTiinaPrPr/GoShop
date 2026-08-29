package org.example.goshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.order.entity.OrderSubmitRecord;

/** 下单幂等事实记录的数据访问层。 */
@Mapper
public interface OrderSubmitRecordMapper extends BaseMapper<OrderSubmitRecord> {

    /**
     * 锁定同一用户、同一幂等键的事实记录。
     *
     * <p>并发插入触发唯一键冲突后使用当前读，确保可以看到先提交事务的完整响应。</p>
     */
    @Select("""
            SELECT *
            FROM order_submit_record
            WHERE user_id = #{userId}
              AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            FOR UPDATE
            """)
    OrderSubmitRecord selectForUpdate(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
