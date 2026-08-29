package org.example.goshop.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已完成订单的商品评价。
 *
 * <p>orderItemId 在数据库中唯一，确保一次购买的一个订单项最多产生一条评价。
 * 商品、用户和订单项均由服务端根据订单事实写入，不能信任客户端传值。</p>
 */
@Data
@TableName("product_review")
public class ProductReview {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderItemId;
    private Long userId;
    private Long spuId;
    private Integer score;
    private String content;
    private String imagesJson;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
