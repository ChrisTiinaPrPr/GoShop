package org.example.goshop.merchant.dto;

import org.example.goshop.merchant.entity.Merchant;

public record MerchantProfileResponse(
        Long id,
        String name,
        String logoUrl,
        String description
) {
    public static MerchantProfileResponse from(Merchant merchant) {
        return new MerchantProfileResponse(
                merchant.getId(),
                merchant.getName(),
                merchant.getLogoUrl(),
                merchant.getDescription()
        );
    }
}
