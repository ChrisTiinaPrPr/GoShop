package org.example.goshop.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.dto.LoginRequst;
import org.example.goshop.auth.dto.LoginResponse;
import org.example.goshop.auth.dto.PortalCodeRequest;
import org.example.goshop.auth.service.AuthService;
import org.example.goshop.common.api.Result;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** 买家端独立认证入口。 */
@RestController
@RequestMapping("/api/v1/buyer/auth")
@RequiredArgsConstructor
public class BuyerAuthController {
    private final AuthService authService;

    @PostMapping("/code")
    public Result<Void> sendCode(@Valid @RequestBody PortalCodeRequest request) {
        authService.sendCode(request.phone(), "BUYER_LOGIN");
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequst request) {
        return Result.ok(authService.loginBuyer(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(40101, "缺少有效登录令牌");
        }
        authService.logout(authorization.substring(7));
        return Result.ok();
    }
}
