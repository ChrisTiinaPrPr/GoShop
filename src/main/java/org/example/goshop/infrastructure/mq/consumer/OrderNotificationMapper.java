package org.example.goshop.infrastructure.mq.consumer;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.security.core.parameters.P;

// 支付成功通知 Mapper
@Mapper
public interface OrderNotificationMapper {

    @Insert("""
        INSERT INTO order_notification
        (
            id,event_id,order_id,
            receiver_type,receiver_id,
            type,title,content,
         read_flag,created_at
        ) VALUES 
              (
                #{id},#{eventId},#{orderId},
               #{receiverType},#{receiverId},
               'PAYMENT_SUCCESS',#{title},#{content},
               0,CURRENT_TIMESTAMP(3)
              )
""")
    int insertNotification(
            @Param("id") Long id,
            @Param("eventId") String eventId,
            @Param("orderId") Long orderId,
            @Param("receiverType") String receiverType,
            @Param("receiverId") Long receiverId,
            @Param("title") String title,
            @Param("content") String content
    );
}
