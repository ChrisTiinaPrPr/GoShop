package org.example.goshop.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("product_image")
public class ProductImage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long spuId;
    private String objectKey;
    private String url;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
