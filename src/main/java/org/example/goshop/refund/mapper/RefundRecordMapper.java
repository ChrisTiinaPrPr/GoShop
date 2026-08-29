package org.example.goshop.refund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.goshop.refund.entity.RefundRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {
    @Select("SELECT * FROM refund_record WHERE refund_no = #{refundNo} FOR UPDATE")
    RefundRecord selectByRefundNoForUpdate(@Param("refundNo") String refundNo);
}
