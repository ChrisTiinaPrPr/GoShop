package org.example.goshop.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_item")
public class OrderItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long spuId;
    private Long skuId;
    private String productTitle;
    private String productImage;
    private String specsJson;
    private Long unitPriceCent;
    private Integer quantity;
    private Long subtotalCent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
