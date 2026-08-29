package org.example.goshop.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
// 与数据库中实际的 payment_record 表保持一致，避免 MyBatis-Plus 生成错误表名。
@TableName("payment_record")
public class PaymentRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String paymentNo;
    private Long orderId;
    private String channel;
    private Long amountCent;
    private String status;
    private String thirdPartyNo;
    private LocalDateTime paidAt;
    private String callbackRaw;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
