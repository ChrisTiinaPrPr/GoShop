package org.example.goshop.auth.dto;

public record LoginResponse(
        String accessToken,
        Long userId,
        String role,
        Long merchantId
) {
}
