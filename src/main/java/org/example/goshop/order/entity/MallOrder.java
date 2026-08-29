package org.example.goshop.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mall_order")
public class MallOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long merchantId;
    private String status;
    private Long totalAmountCent;
    private Long payAmountCent;
    private String addressSnapshotJson;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private String shippingCompany;
    private String trackingNo;
    private LocalDateTime shippedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
