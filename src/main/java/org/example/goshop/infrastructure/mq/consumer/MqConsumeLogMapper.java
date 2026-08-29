package org.example.goshop.infrastructure.mq.consumer;


import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeLogMapper {

    /**
     * 尝试登记某个消费者已经处理了某个业务。
     *
     * <p>返回 1：第一次处理，可以继续执行。</p>
     * <p>返回 0：business_key 已存在，是重复消息，直接正常返回并 ACK。</p>
     *
     * <p>必须在消费者业务事务中调用。如果后续库存恢复失败，
     * 整个事务回滚，这条消费记录也会回滚，消息重试时仍可再次执行。</p>
     */
    @Insert("""
        INSERT IGNORE INTO mq_consume_log
        (consumer_name,business_key,event_id,consumed_at)
        VALUES 
            (#{consumerName},#{businessKey},#{eventId},CURRENT_TIMESTAMP(3))
""")
    int tryConsume(
            @Param("consumerName") String consumerName,
            @Param("businessKey") String businessKey,
            @Param("eventId") String eventId
    );
}
