package org.example.goshop.wallet.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.wallet.dto.WalletBalanceResponse;
import org.example.goshop.wallet.service.WalletService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "钱包")
@RestController
@RequestMapping("/api/v1/buyer/me/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "查询当前用户钱包余额")
    @GetMapping
    public Result<WalletBalanceResponse> getBalance(Authentication authentication) {

        // 用户 ID 必须从 JWT 获取，不能相信前端传入的用户 ID
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(walletService.getBalance(userId));
    }
}
