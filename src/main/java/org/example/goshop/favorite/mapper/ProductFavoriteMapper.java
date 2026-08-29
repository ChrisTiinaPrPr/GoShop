package org.example.goshop.favorite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.favorite.dto.FavoriteProductItem;
import org.example.goshop.favorite.entity.ProductFavorite;

@Mapper
public interface ProductFavoriteMapper extends BaseMapper<ProductFavorite> {

    /**
     * 幂等写入收藏记录。
     *
     * <p>INSERT IGNORE 与 (user_id, spu_id) 唯一键共同处理并发重复请求：
     * 首个请求写入一行，其余请求影响行数为 0，但都可以向客户端返回成功。</p>
     */
    @Insert("""
            INSERT IGNORE INTO product_favorite (id, user_id, spu_id, created_at)
            VALUES (#{id}, #{userId}, #{spuId}, CURRENT_TIMESTAMP(3))
            """)
    int insertIgnore(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("spuId") Long spuId
    );

    /**
     * 仅允许按 JWT 中的用户 ID 删除本人收藏；不存在时影响行数为 0，仍视为幂等成功。
     */
    @Delete("""
            DELETE FROM product_favorite
            WHERE user_id = #{userId}
              AND spu_id = #{spuId}
            """)
    int deleteByUserAndProduct(
            @Param("userId") Long userId,
            @Param("spuId") Long spuId
    );

    @Select("""
            SELECT COUNT(*)
            FROM product_favorite
            WHERE user_id = #{userId}
              AND spu_id = #{spuId}
            """)
    long countByUserAndProduct(
            @Param("userId") Long userId,
            @Param("spuId") Long spuId
    );

    /**
     * 判断商品是否符合项目当前公开商品口径：SPU 上架且至少有一个启用 SKU。
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM product_spu spu
                INNER JOIN product_sku sku
                    ON sku.spu_id = spu.id
                   AND sku.status = 1
                WHERE spu.id = #{spuId}
                  AND spu.status = 1
            )
            """)
    boolean existsPublicProduct(@Param("spuId") Long spuId);

    /**
     * 分页查询当前用户收藏，并实时聚合商品最新数据。
     *
     * <p>这里不按商品状态过滤：已经收藏的商品即使后来下架，也会继续出现在列表中。
     * available 只在 SPU 上架且存在启用 SKU 时为 true；最低价只统计启用 SKU。</p>
     */
    @Select("""
            SELECT
                favorite.spu_id AS product_id,
                spu.merchant_id,
                merchant.name AS merchant_name,
                spu.title,
                spu.main_image,
                MIN(CASE WHEN sku.status = 1 THEN sku.price_cent END) AS min_price_cent,
                CASE
                    WHEN spu.status = 1
                     AND SUM(CASE WHEN sku.status = 1 THEN 1 ELSE 0 END) > 0
                    THEN TRUE
                    ELSE FALSE
                END AS available,
                favorite.created_at AS favorited_at
            FROM product_favorite favorite
            INNER JOIN product_spu spu ON spu.id = favorite.spu_id
            INNER JOIN merchant ON merchant.id = spu.merchant_id
            LEFT JOIN product_sku sku ON sku.spu_id = spu.id
            WHERE favorite.user_id = #{userId}
            GROUP BY
                favorite.id,
                favorite.spu_id,
                favorite.created_at,
                spu.merchant_id,
                spu.title,
                spu.main_image,
                spu.status,
                merchant.name
            ORDER BY favorite.created_at DESC, favorite.id DESC
            """)
    IPage<FavoriteProductItem> selectFavoriteProductPage(
            Page<FavoriteProductItem> page,
            @Param("userId") Long userId
    );
}
