package org.example.goshop.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.payment.entity.PaymentRecord;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {
    @Select("""
            SELECT * FROM payment_record WHERE payment_no = #{paymentNo} FOR UPDATE
            """)
    // 明确绑定参数名，避免编译后未保留形参名时回调查询失败。
    PaymentRecord selectByPaymentNoForUpdate(@Param("paymentNo") String paymentNo);

    /**
     * 订单超时取消后关闭本地未支付的支付单
     */
    @Update("""
        UPDATE payment_record
        SET status = 'CLOSED',
            updated_at = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
          AND status = 'INIT'
        """)
    int closeUnpaidRecordsByOrderId(@Param("orderId") Long orderId);

    @Select("""
    SELECT *
    FROM payment_record
    WHERE payment_no = #{paymentNo}
    LIMIT 1
    """)
    PaymentRecord selectByPaymentNo(
            @Param("paymentNo") String paymentNo
    );
}
