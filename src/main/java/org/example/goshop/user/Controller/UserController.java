package org.example.goshop.user.Controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.user.dto.*;
import org.example.goshop.user.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/buyer")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<UserProfileResponse> getCurrentUser(Authentication authentication){
        // JwtAuthenticationFilter 中已经将 userId 作为 principal 写入。
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.getCurrentUserProfile(userId));
    }

    @Operation(summary = "更新当前用户信息")
    @PatchMapping(
            value = "/me",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Result<UserProfileResponse> updateCurrentUser(
            Authentication authentication,
            @Valid @ModelAttribute UpdateProfileRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateCurrentUser(userId, request));
    }

    @Operation(summary = "获取当前用户地址列表")
    @GetMapping("/me/addresses")
    public Result<List<AddressResponse>> listCurrentUserAddresses(
            Authentication authentication
    ) {
        // userId 来自JWT ，不信任前端传参
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.listCurrentUserAddresses(userId));
    }

    @Operation(summary = "新增当前用户收货地址")
    @PostMapping("/me/addresses")
    public Result<AddressResponse> createCurrentUserAddress(
            Authentication authentication,
            @Valid @RequestBody CreateAddressRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.createCurrentUserAddress(userId, request));
    }

    @Operation(summary = "更新当前用户收货地址")
    @PatchMapping("/me/addresses/{id}")
    public Result<AddressResponse> updateCurrentUserAddress(
            Authentication authentication,
            @PathVariable @Positive(message = "id必须为正数") Long id,
            @Valid @RequestBody UpdateAddressRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateCurrentUserAddress(userId, id, request));
    }

    @Operation(summary = "删除当前用户收货地址")
    @DeleteMapping("/me/addresses/{id}")
    public Result<Void> deleteCurrentUserAddress(
            Authentication authentication,
            @PathVariable @Positive(message = "id必须为正数") Long id
    ) {
        Long userId = (Long) authentication.getPrincipal();
        userService.deleteCurrentUserAddress(userId, id);
        return Result.ok();
    }

    @Operation(summary = "设置当前用户默认收货地址")
    @PatchMapping("/me/addresses/{id}/default")
    public Result<AddressResponse> setCurrentUserDefaultAddress(
            Authentication authentication,
            @PathVariable @Positive(message = "id必须为正数") Long id
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.setCurrentUserDefaultAddress(userId, id));
    }

}
