package org.example.goshop.favorite.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家商品收藏记录。
 *
 * <p>同一用户与商品只能存在一条记录，唯一性由数据库联合唯一索引保证。
 * 收藏记录不会因为商品下架而删除，以便列表向用户展示“已下架”状态。</p>
 */
@Data
@TableName("product_favorite")
public class ProductFavorite {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long spuId;
    private LocalDateTime createdAt;
}
