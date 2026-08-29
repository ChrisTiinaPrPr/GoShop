package org.example.goshop.product.mapper;

import org.example.goshop.product.entity.ProductSku;

public record ProductSkuResponse(
        Long id,
        String specsJson,
        Long priceCent,
        Integer availableStock
) {
    public static ProductSkuResponse from(ProductSku sku) {
        // 锁定库存不可再次购买；防御异常数据导致库存出现负数
        int availableStock = Math.max(sku.getStock() - sku.getLockedStock(),0);
        return new ProductSkuResponse(sku.getId(), sku.getSpecsJson(), sku.getPriceCent(), availableStock);
    }
}
