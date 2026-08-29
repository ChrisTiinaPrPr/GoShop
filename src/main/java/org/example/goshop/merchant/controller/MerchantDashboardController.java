package org.example.goshop.merchant.controller;

import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.MerchantDashboardResponse;
import org.example.goshop.merchant.service.MerchantDashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchant/dashboard")
@RequiredArgsConstructor
public class MerchantDashboardController {
    private final MerchantDashboardService merchantDashboardService;

    @GetMapping
    public Result<MerchantDashboardResponse> dashboard(Authentication authentication) {
        return Result.ok(merchantDashboardService.getDashboard((Long) authentication.getPrincipal()));
    }
}
