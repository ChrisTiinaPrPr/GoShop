package org.example.goshop.merchant.dto;

import org.example.goshop.product.entity.ProductSpu;

public record MerchantProductStatusResponse(
        Long id,
        Integer status
) {
    public static MerchantProductStatusResponse from(ProductSpu spu){
        return new MerchantProductStatusResponse(spu.getId(),spu.getStatus());
    }
}
