package org.example.goshop.merchant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.dto.UpdateMerchantProfileRequest;
import org.example.goshop.merchant.service.MerchantService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "商家店铺资料")
@RestController
@RequestMapping("/api/v1/merchant/me")
@RequiredArgsConstructor
public class MerchantProfileController {
    private final MerchantService merchantService;

    @GetMapping
    @Operation(summary = "获取当前商家店铺资料")
    public Result<MerchantProfileResponse> getProfile(Authentication authentication) {
        return Result.ok(merchantService.getCurrentMerchantProfile((Long) authentication.getPrincipal()));
    }

    @PatchMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "更新当前商家店铺资料")
    public Result<MerchantProfileResponse> updateProfile(
            Authentication authentication,
            @Valid @ModelAttribute UpdateMerchantProfileRequest request
    ) {
        return Result.ok(merchantService.updateCurrentMerchantProfile(
                (Long) authentication.getPrincipal(), request));
    }
}
