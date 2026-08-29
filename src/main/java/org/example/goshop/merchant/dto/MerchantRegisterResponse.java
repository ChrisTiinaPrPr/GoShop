package org.example.goshop.merchant.dto;

import org.example.goshop.merchant.entity.Merchant;

public record MerchantRegisterResponse(
        String accessToken,
        Long userId,
        String role,
        Long id,
        String name,
        String logoUrl,
        String description
) {
    public static MerchantRegisterResponse from(Merchant merchant, String accessToken) {
        return new MerchantRegisterResponse(
                accessToken,
                merchant.getUserId(),
                "MERCHANT",
                merchant.getId(),
                merchant.getName(),
                merchant.getLogoUrl(),
                merchant.getDescription()
        );
    }
}
