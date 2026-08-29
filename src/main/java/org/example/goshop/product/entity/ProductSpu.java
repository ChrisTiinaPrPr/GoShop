package org.example.goshop.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_spu")
public class ProductSpu {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long merchantId;
    private Long categoryId;
    private String title;
    private String description;
    private String mainImage;

    // 0: 下架 1: 上架
    private Integer status;

    private Long salesCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 后续更换或删除商品主图时，用于清理 OSS 对象
    private String mainImageObjectKey;
}
