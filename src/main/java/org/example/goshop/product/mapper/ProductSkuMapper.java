package org.example.goshop.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.product.entity.ProductSku;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {
    @Update("""
            UPDATE product_sku sku
            INNER JOIN product_spu spu ON spu.id = sku.spu_id
            SET sku.stock = sku.stock - #{quantity},
                sku.version = sku.version + 1
            WHERE sku.id = #{skuId}
            AND sku.status = 1
            AND spu.status = 1
            AND sku.stock - sku.locked_stock >= #{quantity}
""")
    int deductAvailableStock(@Param("skuId")Long skuId,@Param("quantity")Integer quantity);

    /**
     * 订单取消后恢复已经扣除的库存
     */
    @Update("""
        UPDATE product_sku
        SET stock = stock + #{quantity},
            version = version + 1
        WHERE id = #{skuId}
        """)
    int restoreStock(
            @Param("skuId") Long skuId,
            @Param("quantity") Integer quantity
    );
}
