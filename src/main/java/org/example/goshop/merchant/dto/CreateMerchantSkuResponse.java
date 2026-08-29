package org.example.goshop.merchant.dto;

import org.example.goshop.product.entity.ProductSku;

public record CreateMerchantSkuResponse(
        Long id,
        String specsJson,
        Long priceCent,
        Integer stock,
        Integer status
) {
    public static CreateMerchantSkuResponse from(ProductSku sku) {
        return new CreateMerchantSkuResponse(
                sku.getId(),
                sku.getSpecsJson(),
                sku.getPriceCent(),
                sku.getStock(),
                sku.getStatus()
        );
    }
}
