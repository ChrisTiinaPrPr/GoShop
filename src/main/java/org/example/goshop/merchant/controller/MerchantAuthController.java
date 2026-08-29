package org.example.goshop.merchant.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.dto.LoginRequst;
import org.example.goshop.auth.dto.LoginResponse;
import org.example.goshop.auth.dto.PortalCodeRequest;
import org.example.goshop.auth.service.AuthService;
import org.example.goshop.common.api.Result;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.MerchantRegisterRequest;
import org.example.goshop.merchant.dto.MerchantRegisterResponse;
import org.example.goshop.merchant.service.MerchantAuthService;
import org.example.goshop.merchant.service.MerchantService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 商家端独立认证与即时开通入口。 */
@RestController
@RequestMapping("/api/v1/merchant/auth")
@RequiredArgsConstructor
public class MerchantAuthController {
    private final AuthService authService;
    private final MerchantAuthService merchantAuthService;
    private final MerchantService merchantService;

    @PostMapping("/code")
    public Result<Void> sendCode(
            @RequestParam(defaultValue = "LOGIN") String purpose,
            @Valid @RequestBody PortalCodeRequest request
    ) {
        String scene = "REGISTER".equalsIgnoreCase(purpose)
                ? "MERCHANT_REGISTER"
                : "MERCHANT_LOGIN";
        authService.sendCode(request.phone(), scene);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequst request) {
        return Result.ok(merchantAuthService.login(request));
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MerchantRegisterResponse> register(@Valid @ModelAttribute MerchantRegisterRequest request) {
        return Result.ok(merchantService.registerMerchant(request));
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
