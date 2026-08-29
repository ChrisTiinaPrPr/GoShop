package org.example.goshop.product.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_sku")
public class ProductSku {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long spuId;

    // MySQL JSON 字段先以字符串返回，前端按需解析规格键值对
    private String specsJson;

    // 价格单位分，避免使用浮点数计算金额
    private Long priceCent;

    private Integer stock;
    private Integer lockedStock;
    private Integer version;

    // 0: 下架 1: 上架
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
