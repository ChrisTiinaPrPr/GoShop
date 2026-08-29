package org.example.goshop.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.merchant.dto.MerchantProductListItem;
import org.example.goshop.merchant.dto.MerchantProductListQuery;
import org.example.goshop.product.dto.ProductListItem;
import org.example.goshop.product.dto.ProductListQuery;
import org.example.goshop.product.entity.ProductSpu;

@Mapper
public interface ProductSpuMapper extends BaseMapper<ProductSpu> {

    @Select("""
            <script>
            SELECT
                spu.id,
                spu.merchant_id,
                spu.category_id,
                spu.title,
                spu.main_image,
                spu.sales_count,
                MIN(sku.price_cent) AS min_price_cent
            FROM product_spu spu
            INNER JOIN product_sku sku
                ON sku.spu_id = spu.id
                AND sku.status = 1
            WHERE spu.status = 1
            <if test="query.merchantId != null">
                AND spu.merchant_id = #{query.merchantId}
            </if>
            <if test="query.minPriceCent != null">
                AND sku.price_cent <![CDATA[ >= ]]> #{query.minPriceCent}
            </if>
            <if test="query.maxPriceCent != null">
                AND sku.price_cent <![CDATA[ <= ]]> #{query.maxPriceCent}
            </if>
            <if test="query.categoryId != null">
                AND spu.category_id = #{query.categoryId}
            </if>
            <if test="query.keyword != null and query.keyword != ''">
                AND spu.title LIKE CONCAT('%', #{query.keyword}, '%')
            </if>
            GROUP BY
                spu.id,
                spu.merchant_id,
                spu.category_id,
                spu.title,
                spu.main_image,
                spu.sales_count,
                spu.created_at
            <choose>
                <when test="query.sort == 'SALES'">
                    ORDER BY spu.sales_count DESC, spu.id DESC
                </when>
                <when test="query.sort == 'PRICE_ASC'">
                    ORDER BY MIN(sku.price_cent) ASC, spu.id DESC
                </when>
                <when test="query.sort == 'PRICE_DESC'">
                    ORDER BY MIN(sku.price_cent) DESC, spu.id DESC
                </when>
                <otherwise>
                    ORDER BY spu.created_at DESC, spu.id DESC
                </otherwise>
            </choose>
            </script>
            """)
    IPage<ProductListItem> selectPublicProductPage(
            Page<ProductListItem> page,
            @Param("query") ProductListQuery query
    );

    @Select("""
        <script>
        SELECT
            spu.id,
            spu.category_id,
            spu.title,
            spu.main_image,
            spu.status,
            spu.sales_count,
            spu.created_at,
            MIN(sku.price_cent) AS min_price_cent,
            MAX(sku.price_cent) AS max_price_cent,
            COUNT(sku.id) AS sku_count
        FROM product_spu spu
        LEFT JOIN product_sku sku
            ON sku.spu_id = spu.id
        WHERE spu.merchant_id = #{query.merchantId}
        <if test="query.categoryId != null">
            AND spu.category_id = #{query.categoryId}
        </if>
        <if test="query.status != null">
            AND spu.status = #{query.status}
        </if>
        <if test="query.keyword != null and query.keyword != ''">
            AND spu.title LIKE CONCAT('%', #{query.keyword}, '%')
        </if>
        GROUP BY
            spu.id,
            spu.category_id,
            spu.title,
            spu.main_image,
            spu.status,
            spu.sales_count,
            spu.created_at
        <choose>
            <when test="query.sort == 'SALES'">
                ORDER BY spu.sales_count DESC, spu.id DESC
            </when>
            <when test="query.sort == 'PRICE_ASC'">
                ORDER BY
                    MIN(sku.price_cent) IS NULL ASC,
                    MIN(sku.price_cent) ASC,
                    spu.id DESC
            </when>
            <when test="query.sort == 'PRICE_DESC'">
                ORDER BY
                    MIN(sku.price_cent) IS NULL ASC,
                    MIN(sku.price_cent) DESC,
                    spu.id DESC
            </when>
            <otherwise>
                ORDER BY spu.created_at DESC, spu.id DESC
            </otherwise>
        </choose>
        </script>
        """)
    IPage<MerchantProductListItem> selectMerchantProductPage(
            Page<MerchantProductListItem> page,
            @Param("query") MerchantProductListQuery query
    );
}
