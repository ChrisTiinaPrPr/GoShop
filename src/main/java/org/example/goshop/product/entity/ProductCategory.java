package org.example.goshop.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_category")
public class ProductCategory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 为 null 时表示平台分类；非 null 时表示商家自己的分类
    private Long merchantId;

    // 为 null 或 0 时表示一级分类
    private Long parentId;

    private String name;

    // 排序,越小优先级越高
    private Integer sort;

    // 0: 禁用；1: 启用
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
